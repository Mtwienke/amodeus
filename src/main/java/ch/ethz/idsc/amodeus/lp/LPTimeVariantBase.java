/* amodeus - Copyright (c) 2018, ETH Zurich, Institute for Dynamic Systems and Control */
package ch.ethz.idsc.amodeus.lp;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.gnu.glpk.GLPK;
import org.gnu.glpk.GLPKConstants;
import org.gnu.glpk.GlpkException;
import org.gnu.glpk.SWIGTYPE_p_double;
import org.gnu.glpk.SWIGTYPE_p_int;
import org.gnu.glpk.glp_prob;
import org.gnu.glpk.glp_smcp;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;

import ch.ethz.idsc.amodeus.util.math.GlobalAssert;
import ch.ethz.idsc.amodeus.virtualnetwork.VirtualNetwork;
import ch.ethz.idsc.tensor.RealScalar;
import ch.ethz.idsc.tensor.Tensor;
import ch.ethz.idsc.tensor.alg.Array;
import ch.ethz.idsc.tensor.alg.Dimensions;
import ch.ethz.idsc.tensor.sca.Chop;
import ch.ethz.idsc.tensor.sca.Round;

abstract class LPTimeVariantBase implements LPSolver {
    protected static final int DURATION = 24 * 60 * 60;
    // ---
    // map with variableIDs in problem set up and linkIDs of virtualNetwork
    protected final Map<List<Integer>, Integer> alphaIDvarID = new HashMap<>();
    protected final Map<List<Integer>, Integer> vIDvarID = new HashMap<>();
    private final glp_smcp parm = new glp_smcp();
    protected final VirtualNetwork<Link> virtualNetwork;
    protected final int nvNodes;
    protected final int timeSteps;
    protected final int timeInterval;
    protected final int numberVehicles;
    protected final int rowTotal;
    protected final int columnTotal;
    protected final Tensor lambdaAbsolute_ij;
    protected final Tensor gamma_ij;
    protected final Tensor timeVS_ij;
    protected final Tensor wAlpha_ij;
    protected final Tensor wLambda_ij;
    protected final Tensor Vmin_i;
    // ---
    protected glp_prob lp;
    protected Tensor alphaAbsolute_ij;
    protected Tensor alphaRate_ij;
    protected Tensor fAbsolute_ij;
    protected Tensor fRate_ij;
    protected Tensor v0_i;
    protected int columnId;
    protected int rowId;

    /** @param virtualNetwork
     * @param network
     * @param lambdaAbsolute_ij has to be integer numbered */
    protected LPTimeVariantBase(VirtualNetwork<Link> virtualNetwork, Network network, Tensor lambdaAbsolute_ij) {
        this.virtualNetwork = virtualNetwork;
        nvNodes = virtualNetwork.getvNodesCount();
        GlobalAssert.that(Chop._04.close(lambdaAbsolute_ij, Round.of(lambdaAbsolute_ij)));
        this.lambdaAbsolute_ij = Round.of(lambdaAbsolute_ij);
        timeSteps = Dimensions.of(lambdaAbsolute_ij).get(0);
        timeInterval = DURATION / timeSteps;
        numberVehicles = LPUtils.getNumberOfVehicles();

        timeVS_ij = getTimeVS_ij();
        gamma_ij = getGamma_ij();
        wAlpha_ij = getwAlpha_ij();
        wLambda_ij = getwLambda_ij();
        Vmin_i = getVmin_i();
        rowTotal = getRowTotal();
        columnTotal = getColumnTotal();

        if (virtualNetwork.getvLinksCount() != (nvNodes * nvNodes - nvNodes)) {
            System.err.println("These computations are only valid for a complete graph. Aborting.");
            GlobalAssert.that(false);
        }
    }

    /** initiate the linear program */
    @Override
    public final void initiateLP() {
        alphaAbsolute_ij = Array.zeros(timeSteps, nvNodes, nvNodes);
        alphaRate_ij = Array.zeros(timeSteps, nvNodes, nvNodes);
        fAbsolute_ij = Array.zeros(timeSteps, nvNodes, nvNodes);
        fRate_ij = Array.zeros(timeSteps, nvNodes, nvNodes);
        v0_i = Array.zeros(nvNodes);

        try {
            lp = GLPK.glp_create_prob();
            System.out.println("initiating time-varying LP with " + timeSteps + " timeSteps, " + numberVehicles + " vehicles and " + nvNodes + " virtual stations");
            GLPK.glp_set_prob_name(lp, "Rebalancing Problem");

            // optimization COLUMN variables alpha and f with constraints >= 0
            GLPK.glp_add_cols(lp, columnTotal);
            columnId = 0;

            initColumnAlpha_ij();
            initColumnF_ij();
            initColumnV_i();

            GlobalAssert.that(columnTotal == columnId);

            // initiate auxiliary ROW variables
            GLPK.glp_add_rows(lp, rowTotal);
            rowId = 0;

            // Allocate memory NOTE: the first value in this array is not used as variables are counted 1,2,3,...,n*n
            SWIGTYPE_p_int ind = GLPK.new_intArray(columnTotal + 1);
            SWIGTYPE_p_double val = GLPK.new_doubleArray(columnTotal + 1);

            initRowF_ij_k(ind, val);
            initRowF_ij_K(ind, val);
            initRowV_i_k(ind, val);
            initRowV_i_K(ind, val);
            initRowN(ind, val);

            GlobalAssert.that(rowTotal == rowId);

            // Free memory
            GLPK.delete_intArray(ind);
            GLPK.delete_doubleArray(val);

            // OBJECTIVE vector
            GLPK.glp_set_obj_name(lp, "z");
            GLPK.glp_set_obj_dir(lp, GLPKConstants.GLP_MIN);

            initObjCr();
            initObjCq();

        } catch (GlpkException ex) {
            System.out.println("error in initialization of LP");
            ex.printStackTrace();
        }
        System.out.println("LP initialization finished");
    }

    @Override
    public void solveLP(boolean mute) {
        System.out.println("solving time-varying LP");
        GLPK.glp_term_out(mute ? GLPK.GLP_OFF : GLPK.GLP_ON);

        GLPK.glp_init_smcp(parm);
        int ret = GLPK.glp_simplex(lp, parm); // ret==0 indicates of the algorithm ran correctly
        GlobalAssert.that(ret == 0);
        int stat = GLPK.glp_get_status(lp);

        if (stat == GLPK.GLP_NOFEAS) {
            System.out.println("LP has found infeasible solution");
            closeLP();
            GlobalAssert.that(false);
        }

        if (stat != GLPK.GLP_OPT) {
            System.out.println("LP has found suboptimal solution");
            closeLP();
            GlobalAssert.that(false);
        }

        readAlpha_ij();
        readF_ij();
        readV0_i();

        closeLP();
    }

    /** closing the LP in order to release allocated memory */
    protected void closeLP() {
        GLPK.glp_delete_prob(lp);
        System.out.println("LP instance is getting destroyed");
    }

    protected Tensor getGamma_ij() {
        return timeVS_ij;
    }

    protected abstract Tensor getTimeVS_ij();

    protected abstract Tensor getwAlpha_ij();

    protected abstract Tensor getwLambda_ij();

    protected abstract Tensor getVmin_i();

    protected abstract int getRowTotal();

    protected abstract int getColumnTotal();

    protected void initColumnAlpha_ij() {
        // optimization variable alpha_ij[k]
        for (int t = 0; t < timeSteps; t++) {
            for (int i = 0; i < nvNodes; ++i) {
                for (int j = 0; j < nvNodes; ++j) {
                    if (j == i)
                        continue;
                    columnId++;
                    // variable name and initialization
                    String varName = ("alpha" + "_" + i + "," + j + "[" + t + "]");
                    GLPK.glp_set_col_name(lp, columnId, varName);
                    GLPK.glp_set_col_kind(lp, columnId, GLPKConstants.GLP_IV); // GLP_IV does only matter for MILP, else its equal to GLP_CV
                    GLPK.glp_set_col_bnds(lp, columnId, GLPKConstants.GLP_LO, 0.0, 0.0); // Lower bound: second number irrelevant
                    alphaIDvarID.put(Arrays.asList(t, i, j), columnId);
                }
            }
        }
        // System.out.println("alpha_ij[k] done");
    }

    protected abstract void initColumnF_ij();

    protected final void initColumnV_i() {
        // optimization variable V_i[0]
        for (int i = 0; i < nvNodes; ++i) {
            columnId++;
            // variable name and initialization
            String varname = ("V_" + i + "[0]");
            GLPK.glp_set_col_name(lp, columnId, varname);
            GLPK.glp_set_col_kind(lp, columnId, GLPKConstants.GLP_IV); // GLP_IV does only matter for MILP, else its equal to GLP_CV
            GLPK.glp_set_col_bnds(lp, columnId, GLPKConstants.GLP_LO, 0.0, 0.0); // Lower bound: second number irrelevant
            vIDvarID.put(Arrays.asList(i), columnId);
        }
        // System.out.println("V_i[0] done");
    }

    protected abstract void initRowV_i_k(SWIGTYPE_p_int ind, SWIGTYPE_p_double val);

    protected abstract void initRowV_i_K(SWIGTYPE_p_int ind, SWIGTYPE_p_double val);

    protected abstract void initRowF_ij_k(SWIGTYPE_p_int ind, SWIGTYPE_p_double val);

    protected abstract void initRowF_ij_K(SWIGTYPE_p_int ind, SWIGTYPE_p_double val);

    protected final void initRowN(SWIGTYPE_p_int ind, SWIGTYPE_p_double val) {
        // row variable N
        rowId++;
        String varName = ("N");
        GLPK.glp_set_row_name(lp, rowId, varName);

        // set fixed bound
        GLPK.glp_set_row_bnds(lp, rowId, GLPKConstants.GLP_FX, numberVehicles, numberVehicles);

        // set the entries of the coefficient matrix A
        // set all to zero first
        for (int var = 1; var <= columnTotal; var++) {
            GLPK.intArray_setitem(ind, var, var);
            GLPK.doubleArray_setitem(val, var, 0.0);
        }

        // set V_i[0]
        for (int i = 0; i < nvNodes; i++) {
            int index = vIDvarID.get(Arrays.asList(i));
            GLPK.intArray_setitem(ind, index, index);
            GLPK.doubleArray_setitem(val, index, 1.0);
        }

        // turn over the entries to GLPK
        GLPK.glp_set_mat_row(lp, rowId, columnTotal, ind, val);

        // System.out.println("N done");
    }

    protected abstract void initObjCr();

    protected abstract void initObjCq();

    protected void readAlpha_ij() {
        for (int t = 0; t < timeSteps; t++) {
            for (int i = 0; i < nvNodes; i++) {
                for (int j = 0; j < nvNodes; j++) {
                    if (i == j)
                        continue;
                    alphaAbsolute_ij.set(RealScalar.of(GLPK.glp_get_col_prim(lp, alphaIDvarID.get(Arrays.asList(t, i, j)))), t, i, j);
                }
            }
        }
        alphaAbsolute_ij = LPUtils.getRoundedRequireNonNegative(alphaAbsolute_ij);
        alphaRate_ij = alphaAbsolute_ij.divide(RealScalar.of(timeInterval));
    }

    protected abstract void readF_ij();

    protected void readV0_i() {
        for (int i = 0; i < nvNodes; i++) {
            v0_i.set(RealScalar.of(GLPK.glp_get_col_prim(lp, vIDvarID.get(Arrays.asList(i)))), i);
        }
        v0_i = LPUtils.getRoundedRequireNonNegative(v0_i);
    }

    @Override
    public Tensor getAlphaAbsolute_ij() {
        return alphaAbsolute_ij;
    }

    @Override
    public Tensor getAlphaRate_ij() {
        return alphaRate_ij;
    }

    @Override
    public Tensor getFAbsolute_ij() {
        return fAbsolute_ij;
    }

    @Override
    public Tensor getFRate_ij() {
        return fRate_ij;
    }

    @Override
    public Tensor getV0_i() {
        return v0_i;
    }

    /** writes the solution of the LP on the consoles */
    @Override
    public void writeLPSolution() {
        System.out.println("The LP solution is:");
        System.out.println("The absolute Rebalancing: " + alphaAbsolute_ij);
        System.out.println("The absolute Customer drives: " + fAbsolute_ij);
        System.out.println("Initial vehicle distribution: " + v0_i);
    }

    @Override
    public int getTimeInterval() {
        return timeInterval;
    }

}
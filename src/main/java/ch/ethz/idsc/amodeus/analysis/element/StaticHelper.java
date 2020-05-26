/* amodeus - Copyright (c) 2018, ETH Zurich, Institute for Dynamic Systems and Control */
package ch.ethz.idsc.amodeus.analysis.element;

import java.util.EnumSet;
import java.util.Map;
import java.util.stream.Collectors;

import ch.ethz.idsc.amodeus.dispatcher.core.RoboTaxiStatus;
import ch.ethz.idsc.amodeus.net.SimulationObject;
import ch.ethz.idsc.tensor.RealScalar;
import ch.ethz.idsc.tensor.Tensor;
import ch.ethz.idsc.tensor.alg.Array;

/* package */ enum StaticHelper {
    ;

    /** @return a {@link Tensor} containing the number of vehicles per
     *         link {@link RoboTaxiStatus} in the {@link SimulationObject} @param simOjb */
    public static Tensor getNumStatus(SimulationObject simOjb) {
        Tensor numPerStatus = Array.zeros(RoboTaxiStatus.values().length);
        // Map<RoboTaxiStatus, List<VehicleContainer>> helpMap = simOjb.vehicles.stream() //
        // .collect(Collectors.groupingBy(vehicleContainer -> vehicleContainer.roboTaxiStatus));
        // for (Entry<RoboTaxiStatus, List<VehicleContainer>> entry : helpMap.entrySet())
        // numPerStatus.set(RealScalar.of(entry.getValue().size()), entry.getKey().ordinal());
        // Map<RoboTaxiStatus, Long> map = simOjb.vehicles.stream() //
        //         .collect(Collectors.groupingBy(vehicleContainer -> vehicleContainer.roboTaxiStatus, Collectors.counting()));
        Map<RoboTaxiStatus, Long> map = simOjb.vehicles.stream() //
                .collect(Collectors.groupingBy(vehicleContainer -> vehicleContainer.statii[vehicleContainer.statii.length - 1], Collectors.counting()));
        map.forEach((roboTaxiStatus, num) -> numPerStatus.set(RealScalar.of(num), roboTaxiStatus.ordinal()));
        return numPerStatus;
    }

    public static String[] descriptions() {
        return EnumSet.allOf(RoboTaxiStatus.class).stream() //
                .map(RoboTaxiStatus::description) //
                .toArray(String[]::new);
    }

}

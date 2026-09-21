package com.taxonomy.export;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

/** Stops the old traversal on its first visit: regression testing never allocates a huge grid. */
final class VisioFiniteGridBudgetChecks {
    private VisioFiniteGridBudgetChecks() {}
    static void rejectsOversizedFiniteBoxesBeforeVisiting() throws Exception {
        var boxClass = Class.forName("com.taxonomy.export.VisioPresentation$Box");
        var box = boxClass.getDeclaredConstructor(double.class, double.class, double.class, double.class); box.setAccessible(true);
        var occupancyClass = Class.forName("com.taxonomy.export.VisioPresentation$Occupancy");
        var constructor = occupancyClass.getDeclaredConstructor(); constructor.setAccessible(true);
        var visitorClass = Class.forName("com.taxonomy.export.VisioPresentation$Occupancy$CellVisitor");
        var visit = occupancyClass.getDeclaredMethod("visit", boxClass, visitorClass); visit.setAccessible(true);
        for (double size : new double[] {10_000, 4_294_967_000d}) {
            var visits = new AtomicInteger();
            Object visitor = Proxy.newProxyInstance(visitorClass.getClassLoader(), new Class<?>[]{visitorClass}, (proxy, method, args) -> {
                visits.incrementAndGet();
                throw new AssertionError("Oversized finite Visio box starts an unbounded scan");
            });
            try {
                visit.invoke(constructor.newInstance(), box.newInstance(0d, 0d, size, size), visitor);
                throw new AssertionError("Oversized finite box accepted");
            } catch (InvocationTargetException failed) {
                if (!(failed.getCause() instanceof IllegalArgumentException)) throw new AssertionError("Finite geometry was not rejected before traversal", failed.getCause());
                if (!failed.getCause().getMessage().contains("budget")) throw new AssertionError("Wrong geometry failure", failed.getCause());
            }
            if (visits.get() != 0) throw new AssertionError("Cells visited before budget rejection");
        }
    }
    public static void main(String[] args) throws Exception {
        rejectsOversizedFiniteBoxesBeforeVisiting();
        VisioGridRegression.rejectsInvalidCoordinates(); VisioGridRegression.acceptsBoundaryCoordinates();
        System.out.println("VISIO_FINITE_GRID_BUDGET_OK");
    }
}

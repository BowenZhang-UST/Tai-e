package sa.pta.analysis.data;

import sa.pta.set.PointsToSet;

public interface Pointer {

    void setPointsToSet(PointsToSet pointsToSet);

    PointsToSet getPointsToSet();
}
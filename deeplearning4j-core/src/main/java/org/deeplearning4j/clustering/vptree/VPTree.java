/*-
 *
 *  * Copyright 2015 Skymind,Inc.
 *  *
 *  *    Licensed under the Apache License, Version 2.0 (the "License");
 *  *    you may not use this file except in compliance with the License.
 *  *    You may obtain a copy of the License at
 *  *
 *  *        http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *    Unless required by applicable law or agreed to in writing, software
 *  *    distributed under the License is distributed on an "AS IS" BASIS,
 *  *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *    See the License for the specific language governing permissions and
 *  *    limitations under the License.
 *
 */

package org.deeplearning4j.clustering.vptree;

import org.deeplearning4j.berkeley.CounterMap;
import org.deeplearning4j.berkeley.PriorityQueue;
import org.deeplearning4j.clustering.sptree.DataPoint;
import org.deeplearning4j.clustering.sptree.HeapItem;
import org.deeplearning4j.util.MathUtils;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Vantage point tree implementation
 *
 * @author Adam Gibson
 */
public class VPTree {

    public static final String EUCLIDEAN = "euclidean";
    private List<DataPoint> items;
    private double tau;
    private Node root;
    private CounterMap<DataPoint, DataPoint> distances;
    private String similarityFunction;
    private boolean invert = true;
    private float distancesArr[];
    private float sortedDistances[];
    private  List<DataPoint> leftPoints,rightPoints;

    public VPTree(INDArray points,boolean invert) {
        this(points,"euclidean",invert);
    }

    /**
     *
     * @param items the items to use
     * @param similarityFunction the similarity function to use
     * @param invert whether to invert the distance (similarity functions have different min/max objectives)
     */
    public VPTree(INDArray items, String similarityFunction, boolean invert) {
        List<DataPoint> thisItems = new ArrayList<>();
        this.similarityFunction = similarityFunction;
        this.invert = invert;
        for (int i = 0; i < items.slices(); i++)
            thisItems.add(new DataPoint(i, items.slice(i), this.similarityFunction, invert));
        this.items = thisItems;
        distances = new CounterMap<>();

        final int deviceId = Nd4j.getAffinityManager().getDeviceForCurrentThread();
 /*       distances = CounterMap.runPairWise(thisItems, new CounterMap.CountFunction<DataPoint>() {
            @Override
            public Float count(DataPoint v1, DataPoint v2) {
                Nd4j.getAffinityManager().attachThreadToDevice(Thread.currentThread(), deviceId);
                return v1.distance(v2);
            }
        });
*/

        root = buildFromPoints(0, this.items.size());
        clearPointsData();
    }

    /**
     *
     * @param items the items to use
     * @param distances the distances
     * @param similarityFunction the similarity function to use
     * @param invert whether to invert the metric (different optimization objective)
     */
    public VPTree(List<DataPoint> items,
                  CounterMap<DataPoint, DataPoint> distances,
                  String similarityFunction,
                  boolean invert) {
        this.items = new ArrayList<>(2 * items.size());
        this.items.addAll(items);
        this.distances = distances;
        this.invert = invert;
        this.similarityFunction = similarityFunction;
        root = buildFromPoints(0, items.size());
        clearPointsData();

    }

    public VPTree(List<DataPoint> items, String similarityFunction, boolean invert) {
        this.items = new ArrayList<>(2 * items.size());
        this.items.addAll(items);
        this.invert = invert;
        this.similarityFunction = similarityFunction;
        distances = new CounterMap<>();
        /*distances = CounterMap.runPairWise(items, new CounterMap.CountFunction<DataPoint>() {
            @Override
            public Float count(DataPoint v1, DataPoint v2) {
                return v1.distance(v2);
            }
        });*/
        root = buildFromPoints(0, items.size());
        clearPointsData();
    }


    public VPTree(INDArray items, String similarityFunction) {
        this(items, similarityFunction, true);
    }

    public VPTree(List<DataPoint> items, CounterMap<DataPoint, DataPoint> distances, String similarityFunction) {
        this(items, distances, similarityFunction, true);

    }

    public VPTree(List<DataPoint> items, String similarityFunction) {
        this(items, similarityFunction, true);
    }


    public VPTree(INDArray items) {
        this(items, EUCLIDEAN);
    }

    public VPTree(List<DataPoint> items, CounterMap<DataPoint, DataPoint> distances) {
        this(items, distances, EUCLIDEAN);

    }

    public VPTree(List<DataPoint> items) {
        this(items, EUCLIDEAN);
    }

    public static INDArray buildFromData(List<DataPoint> data) {
        INDArray ret = Nd4j.create(data.size(), data.get(0).getD());
        for (int i = 0; i < ret.slices(); i++)
            ret.putSlice(i, data.get(i).getPoint());
        return ret;
    }


    public List<DataPoint> getItems() {
        return items;
    }

    public void setItems(List<DataPoint> items) {
        this.items = items;
    }

    private float getDistance(DataPoint d1, DataPoint d2) {
        return  d1.distance(d2);
    }

    //clears out points metadata after points are built
    private void clearPointsData() {
        distancesArr = null;
        sortedDistances = null;
        leftPoints = null;
        rightPoints = null;
    }

    private Node buildFromPoints(int lower, int upper) {
        if (upper == lower)
            return null;
        Node ret = new Node(lower, 0);
        if (upper - lower > 1) {
            int randomPoint = MathUtils.randomNumberBetween(lower, upper - 1);

            // Partition around the median distance
            int median = (upper + lower) / 2;
            if(distancesArr == null)
                distancesArr = new float[items.size()];
            if(sortedDistances == null)
                sortedDistances = new float[items.size()];
            DataPoint basePoint = items.get(randomPoint);

            for (int i = 0; i < items.size(); ++i) {
                distancesArr[i] = (float) getDistance(basePoint, items.get(i));
                sortedDistances[i] = distancesArr[i];
            }

            Arrays.sort(sortedDistances);
            final double medianDistance = sortedDistances[sortedDistances.length / 2];
            //only allocate left/right poitns once
            if(leftPoints == null)
                leftPoints = new ArrayList<>(sortedDistances.length);
            if(rightPoints == null)
                rightPoints = new ArrayList<>(sortedDistances.length);
            if(leftPoints != null)
                leftPoints.clear();
            if(rightPoints != null)
                rightPoints.clear();
            for (int i = 0; i < distancesArr.length; i++) {
                if (distancesArr[i] < medianDistance) {
                    leftPoints.add(items.get(i));
                } else {
                    rightPoints.add(items.get(i));
                }
            }

            for (int i = 0; i < leftPoints.size(); ++i) {
                items.set(i, leftPoints.get(i));
            }

            for (int i = 0; i < rightPoints.size(); ++i) {
                items.set(i + leftPoints.size(), rightPoints.get(i));
            }

            ret.setThreshold(getDistance(items.get(lower), items.get(median)));
            ret.setIndex(lower);
            ret.setLeft(buildFromPoints(lower + 1, median));
            ret.setRight(buildFromPoints(median, upper));



        }

        return ret;

    }


    /**
     *
     * @param target
     * @param k
     * @param results
     * @param distances
     */
    public void search(DataPoint target, int k, List<DataPoint> results, List<Double> distances) {
        PriorityQueue<HeapItem> pq = new PriorityQueue<>();
        tau = Double.MAX_VALUE;
        search(root, target, k, pq);

        results.clear();
        distances.clear();

        while (!pq.isEmpty()) {
            results.add(items.get(pq.peek().getIndex()));
            distances.add(pq.peek().getDistance());
            pq.next();
        }

        if (invert) {
            Collections.reverse(results);
            Collections.reverse(distances);
        }
    }

    /**
     *
     * @param node
     * @param target
     * @param k
     * @param pq
     */
    public void search(Node node, DataPoint target, int k, PriorityQueue<HeapItem> pq) {
        if (node == null)
            return;
        DataPoint get = items.get(node.getIndex());
        double distance = getDistance(get, target);
        if (distance < tau) {
            if (pq.size() == k)
                pq.next();
            pq.add(new HeapItem(node.index, distance), distance);
            if (pq.size() == k)
                tau = pq.peek().getDistance();


        }

        if (node.getLeft() == null && node.getRight() == null)
            return;

        if (distance < node.getThreshold()) {
            if (distance - tau <= node.getThreshold()) { // if there can still be neighbors inside the ball, recursively search left child first
                search(node.getLeft(), target, k, pq);
            }

            if (distance + tau >= node.getThreshold()) { // if there can still be neighbors outside the ball, recursively search right child
                search(node.getRight(), target, k, pq);
            }

        } else {
            if (distance + tau >= node.getThreshold()) { // if there can still be neighbors outside the ball, recursively search right child first
                search(node.getRight(), target, k, pq);
            }

            if (distance - tau <= node.getThreshold()) { // if there can still be neighbors inside the ball, recursively search left child
                search(node.getLeft(), target, k, pq);
            }
        }

    }

    public CounterMap<DataPoint, DataPoint> getDistances() {
        return distances;
    }

    public void setDistances(CounterMap<DataPoint, DataPoint> distances) {
        this.distances = distances;
    }

    public static class Node {
        private int index;
        private float threshold;
        private Node left, right;

        public Node(int index, float threshold) {
            this.index = index;
            this.threshold = threshold;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

            Node node = (Node) o;

            if (index != node.index)
                return false;
            if (Double.compare(node.threshold, threshold) != 0)
                return false;
            if (left != null ? !left.equals(node.left) : node.left != null)
                return false;
            return !(right != null ? !right.equals(node.right) : node.right != null);

        }

        @Override
        public int hashCode() {
            int result;
            long temp;
            result = index;
            temp = Double.doubleToLongBits(threshold);
            result = 31 * result + (int) (temp ^ (temp >>> 32));
            result = 31 * result + (left != null ? left.hashCode() : 0);
            result = 31 * result + (right != null ? right.hashCode() : 0);
            return result;
        }

        public int getIndex() {
            return index;
        }

        public void setIndex(int index) {
            this.index = index;
        }

        public float getThreshold() {
            return threshold;
        }

        public void setThreshold(float threshold) {
            this.threshold = threshold;
        }

        public Node getLeft() {
            return left;
        }

        public void setLeft(Node left) {
            this.left = left;
        }

        public Node getRight() {
            return right;
        }

        public void setRight(Node right) {
            this.right = right;
        }
    }

}

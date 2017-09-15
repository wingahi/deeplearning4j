/*-
 *
 *  * Copyright 2016 Skymind,Inc.
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

package org.deeplearning4j.nn.graph.vertex.impl;

import org.deeplearning4j.nn.api.Layer;
import org.deeplearning4j.nn.api.MaskState;
import org.deeplearning4j.nn.api.activations.Activations;
import org.deeplearning4j.nn.api.activations.ActivationsFactory;
import org.deeplearning4j.nn.api.gradients.Gradients;
import org.deeplearning4j.nn.api.gradients.GradientsFactory;
import org.deeplearning4j.nn.gradient.Gradient;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.graph.vertex.BaseGraphVertex;
import org.deeplearning4j.nn.graph.vertex.VertexIndices;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.primitives.Pair;

/**
 * Adds the ability to reshape and flatten the tensor in the computation graph. This is the equivalent
 * of calling {@code .reshape(new int[]{})} on the input array to the vertex and passing the new shape
 * to the next layer. ReshapeVertex also ensures the shape is valid for the backward pass.
 *
 * @author Justin Long (crockpotveggies)
 */
public class ReshapeVertex extends BaseGraphVertex {

    private char order;
    private int[] newShape;
    private int[] maskShape;


    public ReshapeVertex(ComputationGraph graph, String name, int vertexIndex, int numInputs, char order, int[] newShape, int[] maskShape) {
        super(graph, name, vertexIndex, numInputs);
        this.order = order;
        this.newShape = newShape;
        this.maskShape = maskShape;
    }

    @Override
    public Activations activate(boolean training) {
        if (!canDoForward())
            throw new IllegalStateException("Cannot do forward pass: inputs not set");

        if (inputs.length > 1)
            throw new IllegalStateException("Reshape vertex requires a single input.");


        return ActivationsFactory.getInstance().create(inputs[0].reshape(order, newShape));
    }

    @Override
    public Gradients backpropGradient(Gradients gradients) {
        if (!canDoBackward())
            throw new IllegalStateException("Cannot do backward pass: errors not set");

        INDArray out = gradients.get(0).reshape(order, inputs[0].shape());
        return GradientsFactory.getInstance().create(out, null);
    }

    @Override
    public void setBackpropGradientsViewArray(INDArray backpropGradientsViewArray) {
        if (backpropGradientsViewArray != null)
            throw new RuntimeException("Vertex does not have gradients; gradients view array cannot be set here");
    }

    @Override
    public Pair<INDArray, MaskState> feedForwardMaskArrays(INDArray[] maskArrays, MaskState currentMaskState,
                    int minibatchSize) {
        if (maskArrays == null || maskArrays.length < 1 || maskArrays[0] == null) {
            return new Pair<>(null, currentMaskState);
        }

        if(maskShape != null){
            return new Pair<>(maskArrays[0].reshape(order, maskShape), currentMaskState);
        }

        //Mask array is an input mask. Therefore: 2 possible cases
        //(a) column vector mask (MLP, CNN), and
        //  i. output is rank 2 or 4 (MLP, CNN) -> no change
        // ii. output is rank 3 (RNN) -> to 2d
        //(b) 2d mask (RNN), and
        //  i. output is rank 2 or 4 (MLP, CNN) -> mask to column vector
        // ii. output is rank 3 (RNN) -> no change


        if(maskArrays[0].isColumnVector()){
            if(newShape.length == 2 || newShape.length == 4){
                return new Pair<>(maskArrays[0], currentMaskState);
            } else if(newShape.length == 3) {
                //Column vector -> 2d (FF -> RNN etc)
                int[] newMaskShape = new int[]{newShape[0], newShape[2]};
                return new Pair<>(maskArrays[0].reshape(order, newMaskShape), currentMaskState);
            }
        } else {
            if(newShape.length == 3){
                return new Pair<>(maskArrays[0], currentMaskState);
            } else {
                //RNN -> FF/CNN
                int[] newMaskShape = new int[]{newShape[0]*newShape[2], 1};
                return new Pair<>(maskArrays[0].reshape(order, newMaskShape), currentMaskState);
            }
        }

        //Other unknown case - shouldn't happen...
        return new Pair<>(maskArrays[0], currentMaskState);
    }

    @Override
    public String toString() {
        return "ReshapeVertex(id=" + this.getIndex() + ",name=\"" + this.getName() + "\",shape="
                        + newShape.toString() + ")";
    }
}

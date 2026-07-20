// keypoint_classifier.hdf5 / point_history_classifier.hdf5는 Dense 3개짜리 순수 MLP라
// tfjs 없이 행렬곱만으로 forward pass를 그대로 구현했다 (Dropout은 추론 시 no-op이라 생략).
export interface DenseLayerWeights {
  kernel: number[][]; // [inputDim][outputDim], Keras kernel 그대로
  bias: number[];
}

function relu(x: number): number {
  return x > 0 ? x : 0;
}

function denseForward(input: number[], layer: DenseLayerWeights, activation: 'relu' | 'linear'): number[] {
  const outputDim = layer.bias.length;
  const output = new Array<number>(outputDim);
  for (let o = 0; o < outputDim; o++) {
    let sum = layer.bias[o];
    for (let i = 0; i < input.length; i++) {
      sum += input[i] * layer.kernel[i][o];
    }
    output[o] = activation === 'relu' ? relu(sum) : sum;
  }
  return output;
}

function softmax(logits: number[]): number[] {
  const max = Math.max(...logits);
  const exps = logits.map((v) => Math.exp(v - max));
  const sum = exps.reduce((a, b) => a + b, 0);
  return exps.map((v) => v / sum);
}

// dense(relu) -> dense_1(relu) -> dense_2(softmax) 고정 구조.
export function mlpForward(input: number[], layers: DenseLayerWeights[]): number[] {
  const hidden1 = denseForward(input, layers[0], 'relu');
  const hidden2 = denseForward(hidden1, layers[1], 'relu');
  const logits = denseForward(hidden2, layers[2], 'linear');
  return softmax(logits);
}

export function argmax(values: number[]): number {
  let bestIndex = 0;
  for (let i = 1; i < values.length; i++) {
    if (values[i] > values[bestIndex]) bestIndex = i;
  }
  return bestIndex;
}

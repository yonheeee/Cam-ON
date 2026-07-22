#!/usr/bin/env python
# keypoint_classifier.hdf5(Dense 3개짜리 순수 MLP)의 kernel/bias만 뽑아
# frontend/src/features/gesture/lib/mlp.ts가 기대하는 형식(JSON)으로 export.
# tensorflow 없이 h5py만으로 끝낸다 (frontend/HANDOFF.md에 기록된 것과 동일한 방식).
import json
import h5py

SRC = "model/keypoint_classifier/keypoint_classifier.hdf5"
DST = "../frontend/src/features/gesture/models/keypoint_classifier_weights.json"
LAYER_NAMES = ["dense", "dense_1", "dense_2"]

with h5py.File(SRC, "r") as f:
    layers = []
    for name in LAYER_NAMES:
        group = f[f"model_weights/{name}/{name}"]
        kernel = group["kernel:0"][()]
        bias = group["bias:0"][()]
        layers.append({
            "kernel": kernel.tolist(),
            "bias": bias.tolist(),
        })

with open(DST, "w", encoding="utf-8") as out:
    json.dump(layers, out)

print(f"Exported {len(layers)} layers to {DST}")
for i, layer in enumerate(layers):
    print(f"  layer {i}: kernel {len(layer['kernel'])}x{len(layer['kernel'][0])}, bias {len(layer['bias'])}")

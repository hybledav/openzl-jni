// Copyright (c) Meta Platforms, Inc. and affiliates.

import {Codec} from '../../models/Codec';
import type {InternalGraphNode} from './InternalGraphNode';
import {InternalNode} from './InternalNode';
import type {NodeType} from './types';
import type {RF_codecId} from './types';

export class InternalCodecNode extends InternalNode {
  readonly codec: Codec;
  readonly parentGraph: InternalGraphNode | null = null;
  // Properties for node collapsing
  // isCollapsed: boolean = false;
  // isHidden: boolean = false;

  constructor(id: RF_codecId, type: NodeType, codec: Codec, parentGraph: InternalGraphNode | null) {
    super(id, type);
    this.id = id;
    this.codec = codec;
    this.parentGraph = parentGraph;
  }
}

// Copyright (c) Meta Platforms, Inc. and affiliates.

import {NodeType} from './types';
import type {RF_nodeId} from './types';

export class InternalNode {
  // React Flow properties
  id: RF_nodeId;
  type: NodeType;
  isCollapsed: boolean = false;
  isVisible: boolean = true;
  inLargestCompressionPath: boolean = false;

  constructor(id: RF_nodeId, type: NodeType) {
    this.id = id;
    this.type = type;
  }
}

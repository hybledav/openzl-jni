// Copyright (c) Meta Platforms, Inc. and affiliates.

import {Codec} from './Codec';
import type {CodecID} from './idTypes';
import type {Stream} from './Stream';

/**
 * A DAG of nodes for easier traversal of the graph. This is necessary because
 * the @ref Codec and @ref Stream classes aren't meant to be used directly for
 * graph traversal.
 */
export class CodecDag {
  private readonly adjList: Map<CodecID, Set<CodecID>>;
  private readonly dagOrderList: CodecID[];

  constructor(codecs: Codec[], streams: Stream[]) {
    this.adjList = new Map();
    codecs.forEach((codec) => {
      let childSet = new Set<CodecID>();
      for (const streamId of codec.outputStreams) {
        if (streams[streamId] == undefined) {
          // because of temp hack
          continue;
        }
        const targetCodec = streams[streamId].targetCodec;
        childSet.add(targetCodec);
      }
      this.adjList.set(codec.id, childSet);
    });
    console.assert(codecs.length === this.adjList.size);

    // Ensure codec at index 0 is the root
    console.assert(codecs[0].inputStreams.length === 0);

    // generate dag order
    this.dagOrderList = this.findTopologicalSort(this.adjList);
  }

  dagOrder(): CodecID[] {
    return this.dagOrderList;
  }

  reverseDagOrder(): CodecID[] {
    return Array.from(this.dagOrderList).reverse();
  }

  getChildren(codecId: CodecID): CodecID[] {
    return Array.from(this.adjList.get(codecId) ?? []);
  }

  private findTopologicalSort(adjList: Map<CodecID, Set<CodecID>>): CodecID[] {
    let tSort: CodecID[] = [];
    let inDegree: Map<CodecID, number> = new Map();

    // find in-degree for each vertex
    adjList.forEach((edges, vertex) => {
      // If vertex is not in the map, add it to the inDegree map
      if (!inDegree.has(vertex)) {
        inDegree.set(vertex, 0);
      }

      edges.forEach((edge) => {
        // Increase the inDegree for each edge
        if (inDegree.has(edge)) {
          inDegree.set(edge, inDegree.get(edge)! + 1);
        } else {
          inDegree.set(edge, 1);
        }
      });
    });

    // Queue for holding vertices that has 0 inDegree Value
    let queue: CodecID[] = [];
    inDegree.forEach((degree, vertex) => {
      // Add vertices with inDegree 0 to the queue
      if (degree == 0) {
        queue.push(vertex);
      }
    });

    // Traverse through the leaf vertices
    while (queue.length > 0) {
      let current = queue.shift()!;
      tSort.push(current);
      // Mark the current vertex as visited and decrease the inDegree for the edges of the vertex
      // Imagine we are deleting this current vertex from our graph
      if (adjList.has(current)) {
        adjList.get(current)!.forEach((edge) => {
          if (inDegree.has(edge) && inDegree.get(edge)! > 0) {
            // Decrease the inDegree for the adjacent vertex
            let newDegree = inDegree.get(edge)! - 1;
            inDegree.set(edge, newDegree);

            // if inDegree becomes zero, we found new leaf node.
            // Add to the queue to traverse through its edges
            if (newDegree == 0) {
              queue.push(edge);
            }
          }
        });
      }
    }
    return tSort;
  }
}

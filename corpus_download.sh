#!/bin/bash
# Copyright (c) Meta Platforms, Inc. and affiliates.

# REMOVE BEFORE OPEN-SOURCING

# This script is used to download the data from manifold onto your local machine.
# Ensure you have the manifold cli installed on your machine.
# https://www.internalfb.com/wiki/Manifold/Getting_Started/#cli

BUCKET=openzl_corpus
CORPUS_ROOT=_corpus

# Function to download a single corpus
download_corpus() {
  CORPUS=$1
  mkdir -p "$CORPUS_ROOT/$CORPUS"
  pushd "$CORPUS_ROOT" || return

  echo "Downloading $CORPUS corpus files from manifold bucket $BUCKET..."
  pushd "$CORPUS" || return
  manifold --prod-use-cython-client getr "$BUCKET/tree/$CORPUS"
  for file in *.zst; do
    zstd -d "$file"
    rm "$file"
  done

  echo "Downloaded files to $(pwd)"
  popd || return
  popd || return
}

for bucket in "$@"; do
  download_corpus "$bucket"
done

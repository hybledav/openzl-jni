# openzl-bench

Extensible protobuf codec benchmark module comparing:

- `openzl-trained-*` candidates (fast-enc/fast-dec/best-ratio/balanced/speed)
- `openzl-untrained`
- `gzip`
- `zstd`
- `lz4`
- `pure` (no compression baseline)

## Run

From repo root:

```bash
mvn -f JNI/pom.xml -pl openzl-jni -am -Dnative.build=true -DskipTests package
mvn -f JNI/pom.xml -pl openzl-bench -am -DskipTests exec:java
```

Optional dataset override:

```bash
mvn -f JNI/pom.xml -pl openzl-bench -am -DskipTests exec:java -Dsao.path=/path/to/sao.bz2
```

## Tunables

- `-Dbench.payload.mode=sao-batch` (`sao-batch` reuses shared SAO batch generator from `openzl-utils`; `dynamic` keeps synthetic benchmark schema)
- `-Dbench.payload.count=256`
- `-Dbench.payload.chunk.bytes=4096`
- `-Dbench.warmup.iterations=1000`
- `-Dbench.measure.iterations=10000`
- `-Dbench.threads=4`
- `-Dbench.openzl.train.max.secs=30`
- `-Dbench.openzl.train.threads=4`
- `-Dbench.openzl.train.samples=256`
- `-Dbench.openzl.train.pareto=false`
- `-Dbench.openzl.candidates=5`
- `-Dbench.rank.weight.encode=0.45`
- `-Dbench.rank.weight.decode=0.25`
- `-Dbench.rank.weight.ratio=0.30`
- `-Dbench.openzl.inspect=true`
- `-Dbench.openzl.inspect.dir=.bench-logs/openzl-graphs`

## Extending with new codecs

1. Implement `Codec` in `src/main/java/io/github/hybledav/bench/protobuf/`.
2. Register it in `CodecRegistry.defaultCodecs(...)`.
3. Re-run benchmark.

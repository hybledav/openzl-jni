# OpenZL JNI 0.2.0-1 versus 0.3.0-1: quick benchmark

Run on 2026-11-14 using published Maven Central artifacts on Linux x86_64, AMD Ryzen 9 7900, Java 21.0.9. These are synthetic smoke benchmarks, not production-workload predictions.

## Percentage changes

Positive speed changes mean faster; negative compressed-size changes mean smaller. All round trips were verified.

| Workload | Compression speed | Decompression speed | Compressed size |
| --- | ---: | ---: | ---: |
| JSON / ZSTD L6 | +1.6% | -0.4% | +0.00% |
| Random bytes / ZSTD L6 | +0.1% | -1.8% | +0.00% |
| Counters / NUMERIC L6 | +11.6% | +35.7% | -0.01% |
| Sparse integers / NUMERIC L6 | +33.3% | -3.0% | -0.92% |
| Counters / NUMERIC L7 | +1.1% | +62.2% | -1.65% |

## Absolute median rates

| Workload | Compress old/new MB/s | Decompress old/new MB/s | Compressed old/new bytes |
| --- | ---: | ---: | ---: |
| JSON / ZSTD L6 | 305.3 / 310.1 | 5599.7 / 5576.6 | 15181 / 15181 |
| Random bytes / ZSTD L6 | 3562.6 / 3566.5 | 15704.1 / 15426.7 | 262168 / 262168 |
| Counters / NUMERIC L6 | 153.5 / 171.4 | 1923.9 / 2610.5 | 22171 / 22168 |
| Sparse integers / NUMERIC L6 | 431.9 / 575.7 | 4505.8 / 4371.8 | 10900 / 10800 |
| Counters / NUMERIC L7 | 124.2 / 125.6 | 1947.8 / 3159.0 | 22171 / 21806 |

## Method and limits

256 KiB seeded synthetic blocks; single worker; cores 2,3; SerialGC; 512 MiB heap; 0.5s warmup and 5x0.3s measurement per operation; 3 fresh JVM forks per release, alternating order; median of per-fork medians; every workload round-trip verified.

The ZSTD byte workloads use reusable destination arrays. Numeric workloads use the public allocating typed-array API, so their rates include JNI/Java allocation and GC costs. Old/new runs use the same approach for each workload. Throughput is measured in uncompressed decimal MB/s.

Both releases use their shipped defaults for frame format: 24 for 0.2.0-1 and 27 for 0.3.0-1. This measures the released JNI packages together with their native libraries; it does not isolate upstream engine changes from binding changes.

Requested level 7 is a behavior comparison: 0.3.0 adds the numeric Transformer and fixes compression-level propagation. The old binding could override the requested level. The L7 compression-rate medians varied noticeably across new-version forks (119–135 MB/s), so the small overall change is inconclusive.

The old release does not expose an LZ profile; no LZ old/new percentage is claimed. A new-version-only LZ sample is retained in raw logs. Small timing differences of roughly 1–3% should be treated cautiously on this desktop host.

## Upstream changes

OpenZL 0.3.0 upgrades the LZ engine/training, adds the numeric Compression Transformer (level 7+), dictionary bundles, sparse numeric codecs, serializable brute-force graphs and training controls. The default frame format changes from 24 to 27. Producing data for a 0.2.0 reader requires explicitly selecting format 24 and a compatible graph.

Source: [OpenZL v0.3.0 release notes](https://github.com/meta/openzl/releases/tag/v0.3.0).

## Workloads and artifact provenance

All inputs use Java Random seed 310024. JSON contains repeated telemetry records with varying timestamps, device IDs, regions, temperatures and status. The random-byte case is an incompressible control. Counter arrays contain 65,536 values of the form i * 4 plus random jitter from 0 to 3. Sparse arrays contain 95% zeros, with the other values drawn from 0–4095. The two counter-level cases use the same input array.

[Machine-readable results](BENCHMARK_0_3_0_1.json) record per-fork medians, runtime details and SHA-256 values for the four published JARs. The old JARs match the pinned public 0.2.0-1 compatibility baseline. The comparison uses public methods common to both releases. The optional new-only LZ setup uses reflection outside its timing window because the old Java enum has no LZ entry; that case is excluded from the comparison table.

The benchmark does not modify library code or release configuration.

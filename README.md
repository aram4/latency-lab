# latency-lab

I'm curious how HFT and other low-latency, high-consistency distributed systems hit the
numbers they do — industry standard is C++ (and FPGAs at the extreme). I wanted to see how
close a more widely-used language could get. Python was the first thing that came to mind,
but garbage collection and per-object interpreter overhead rule it out before you even start
measuring. Java seemed like the more realistic contender, so this is that experiment.

This repo builds the same limit order book (matching engine) twice. The first version is idiomatic,
readable Java. The second is written the way low-latency Java is actually written. Both are
measured with a harness designed not to lie about tail latency. Every result is reproducible
from `results/summary.csv`.

## Why Java (and not C++)?

Java isn't a toy choice here. LMAX built an exchange on it (and open-sourced the Disruptor from
that work), and Chronicle, Aeron and Agrona exist because firms run Java on latency-critical
paths. Java gives up the last few hundred nanoseconds to C++ and FPGAs at the true tick-to-trade
extreme, but it offers faster development and memory safety.

The catch, and the point of this project, is that *low-latency Java doesn't look like normal Java*.
The idiomatic version below is readable and slow in the tail. The fast version keeps
the language but drops the habits: no allocation on the hot path, primitive arrays instead of
object graphs, and callbacks instead of return values. Finding where that line sits is the project.

## Quick start

Requires JDK 21+. There are no other dependencies.

```bash
./run.sh verify                                    # correctness gate: run this first, always
./run.sh bench                                     # naive vs fast, closed loop
./run.sh bench --mode open --rate 500000           # open loop, coordinated-omission aware
./run.sh bench --mode hiccup --seconds 10          # how much jitter does this machine add?
JVM_OPTS="-XX:+UseZGC" ./run.sh bench --label zgc  # experiment with JVM flags; results are tagged
```

## Results so far

> Machine: 2 vCPU cloud VM (Xeon @ 2.1GHz), JDK 21, `-Xms2g -Xmx2g -XX:+AlwaysPreTouch`,
> 2M measured events after 500k warm-up. These are single runs on a noisy VM. Treat the
> tails as indicative, not final (see "Open questions").

**Closed loop (service time per operation):**

| engine | p50 | p99 | p99.9 | p99.99 | max | throughput | alloc/op | GCs |
|---|---|---|---|---|---|---|---|---|
| naive (TreeMap/HashMap/objects) | 173 ns | 1.09 µs | 4.06 µs | 43 µs | **25.7 ms** | 2.9M ops/s | 120 B | 2 |
| fast (arrays, pooled, zero-alloc) | 54 ns | 439 ns | 863 ns | 17.9 µs | **0.33 ms** | 8.3M ops/s | **0 B** | **0** |
| fast + Epsilon GC (no-op GC) | 47 ns | 287 ns | 575 ns | 12.4 µs | 0.61 ms | 10.2M ops/s | 0 B | 0 |

**Open loop at 500k orders/s: response time vs. service time**

| engine | measured as | p50 | p99 | p99.9 | max |
|---|---|---|---|---|---|
| naive | service time (what a naive harness reports) | 209 ns | 1.4 µs | 4.3 µs | 16.2 ms |
| naive | **response time** (what a client experiences) | 263 ns | **3.47 ms** | **13.0 ms** | 16.2 ms |
| fast | service time | 70 ns | 559 ns | 1.07 µs | 1.7 ms |
| fast | **response time** | 97 ns | **182 µs** | **1.56 ms** | 2.6 ms |

**Platform jitter probe (spin loop, no engine, no allocation, 10s):** p99.999 = 25 µs,
max = 4.6 ms, **22 stalls > 1 ms**.

**Local run (closed loop, Apple Silicon Mac, 10 cpus, JDK 21):**

| engine | p50 | p99 | p99.9 | p99.99 | max | throughput | alloc/op | GCs |
|---|---|---|---|---|---|---|---|---|
| naive | 83 ns | 631 ns | 959 ns | 5.18 µs | **2.60 ms** | 7.7M ops/s | 120 B | 2 |
| fast | 41 ns | 125 ns | 209 ns | 631 ns | **24.8 µs** | 18.6M ops/s | **0 B** | **0** |

Same pattern as the cloud VM: fast is only ~2x faster at p50, but ~100x better at max latency,
because it never allocates and never triggers a GC. Absolute numbers aren't comparable
across machines — this is a different CPU and a much quieter box — but the shape of the
result (median gap is small, tail gap is enormous) reproduces. Further testing (more seeds,
repeated runs, open-loop and hiccup-probe numbers on this machine, Phase 2 of the roadmap)
is ongoing; treat both tables as checkpoints, not final numbers.

## What I've learned so far

1. **Averages and medians hide the problem.** Both engines have sub-microsecond p99 in the closed
   loop. The difference shows up at p99.99 and max, where the naive engine's GC pauses sit.
2. **Coordinated omission is real and large.** For the same run, the naive engine's p99 is
   1.4 µs measured as service time and **3.47 ms** measured as response time, a ~2,500× gap.
   A closed-loop benchmark stops sending during a stall, so it never records the orders that
   would have queued behind it.
3. **Zero allocation is what removes the GC tail.** Making the code "faster" on average is secondary.
   The fast engine allocates 0 bytes/op, so the GC never runs and the 25 ms max disappears.
4. **Zero allocation also makes the GC optional.** The fast engine runs under Epsilon, a GC that
   never collects, and gets *faster*, because G1's write barriers are no longer paid on every
   reference store. That's a measurable cost of the collector even when it isn't collecting.
5. **Past a point, the machine is the bottleneck, not the code.** The fast engine still sees
   millisecond stalls in the open loop with zero GC. The hiccup probe shows the VM itself
   stalls >1 ms about twice per second. No Java change will fix that. It's an OS, hypervisor, and
   core-isolation problem, which is the next phase.

## How the measurement works (and why)

- **Pre-generated workload.** Orders are generated up front into primitive arrays, so the timed
  region measures the engine, not the random-number generator.
- **Warm-up on the same code path.** 500k events run through the identical loop first, so the
  JIT has compiled what we then measure.
- **Log-linear histogram** (`LatencyHistogram`, HdrHistogram-style, ~1.6% precision). Recording is
  O(1) and allocation-free, so measuring doesn't create the tail it's trying to observe.
- **Open loop with intended start times.** Latency is `end - intended_send_time`, so time spent
  queued behind a stall is counted.
- **Allocation and GC counters** (`ThreadMXBean.getCurrentThreadAllocatedBytes`, GC MXBeans) are
  reported with every run, so "zero-allocation" is a measured claim, not an assertion.
- **Differential correctness test.** Both engines replay the same random order flow across five
  seeds and must produce identical fill sequences (order-sensitive checksum), plus hand-written
  price-time-priority scenarios. An optimisation that changes a single fill fails.

## Design: naive vs. fast

| concern | naive | fast |
|---|---|---|
| price levels | `TreeMap<Long, ArrayDeque<Order>>` | `int[]` head/tail indexed by price tick |
| orders | `Order` object per order | pre-allocated pool, struct-of-arrays |
| queue per level | `ArrayDeque`, O(n) cancel | intrusive doubly linked list, O(1) cancel |
| id lookup | `HashMap<Long, Order>` (boxing) | `int[] idToSlot` (gateway assigns ids) |
| fills | `new ArrayList<Fill>()` per call | primitive callback, nothing materialised |
| best price | `TreeMap.firstEntry()` | tracked index, scan on level empty |

Trade-offs the fast version accepts: a bounded price band, a fixed pool size, and less
readable code. Each is a deliberate choice that a real venue makes too.

## What's next

This is a personal project, so the plan is loose: more JVM/GC experiments (G1 vs ZGC vs Epsilon),
a run on bare-metal Linux with core isolation to separate JVM jitter from platform jitter, and
eventually splitting gateway and engine across a ring buffer to see what a pipeline costs.
Further testing is ongoing — treat everything in `results/` as a checkpoint, not a final answer.

## Open questions

- How much of the fast engine's open-loop tail is VM jitter vs. something in the JVM
  (safepoints, JIT deoptimisation)? Needs `-Xlog:safepoint` plus a quieter machine.
- The naive engine's p99.9 is only ~4× worse than fast in the closed loop. Is the gap bigger
  with a deeper book (more levels, more resting orders)?

## Layout

```
src/main/java/lab/
  engine/Engine.java              common interface + matching semantics
  engine/FillSink.java            primitive fill callback + checksum
  engine/naive/NaiveOrderBook.java
  engine/fast/FastOrderBook.java
  harness/Workload.java           deterministic pre-generated order flow
  harness/LatencyHistogram.java   allocation-free log-linear histogram
  harness/Bench.java              closed / open / hiccup modes
  harness/Verify.java             correctness gate
results/                          per-run percentile CSVs + summary.csv
```

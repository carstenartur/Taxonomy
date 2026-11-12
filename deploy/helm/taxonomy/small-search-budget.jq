# First two readings bracket the idle interval. The remaining readings bracket searches.
def counter: type == "number" and . >= 0 and . == floor;
def valid: [.ms, .cpuUsec, .throttledPeriods, .throttledUsec, .oomKills,
            .memoryBytes, .ephemeralBytes, .restarts] | all(.[]; counter);
. as $samples
| if length < 3 or any(.[]; (valid | not) or .restarts != 0 or .oomKills != 0)
  then error("missing, invalid, OOM or restarted sample") else . end
| [range(1; length) as $i
   | $samples[$i - 1] as $a | $samples[$i] as $b
   | if $b.ms <= $a.ms or $b.cpuUsec < $a.cpuUsec or
        $b.throttledPeriods < $a.throttledPeriods or $b.throttledUsec < $a.throttledUsec
     then error("nonmonotonic sampling interval") else
     (($b.cpuUsec - $a.cpuUsec) / ($b.ms - $a.ms)) end] as $cpu
| {samples: length, steadyCpuMillicores: $cpu[0], peakIntervalCpuMillicores: ($cpu[1:] | max),
   steadyMemoryBytes: .[1].memoryBytes, peakSampledMemoryBytes: (map(.memoryBytes) | max),
   peakSampledEphemeralBytes: (map(.ephemeralBytes) | max),
   throttledPeriods: (.[-1].throttledPeriods - .[0].throttledPeriods),
   throttledUsec: (.[-1].throttledUsec - .[0].throttledUsec),
   oomKills: .[-1].oomKills, restarts: .[-1].restarts}

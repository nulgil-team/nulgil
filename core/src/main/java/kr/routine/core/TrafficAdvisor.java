package kr.routine.core;
import java.util.*;
import java.time.*;
import kr.routine.core.Model.*;
public final class TrafficAdvisor {
    public Advice advise(Estimate current,List<Estimate> history,long now,ZoneId zone) {
        Objects.requireNonNull(zone, "zone");
        if (current == null) return new Advice("현재 교통 정보를 받지 못했습니다.", null, false, TrafficKind.MISSING, List.of());
        long currentAge = now - current.observedAt();
        if (currentAge < 0 || currentAge > 120_000)
            return new Advice("교통 정보의 시각을 확인할 수 없거나 오래됐습니다. 다시 조회해 주세요.", null, false, TrafficKind.STALE, List.of());
        if (current.minutes() < 1)
            return new Advice("예상 시간의 값 또는 단위를 확인하지 못해 비교를 보류합니다.", null, false, TrafficKind.UNKNOWN_UNITS, List.of());
        String prefix = (current.demo() ? "모의 " : "") + "예상 " + current.minutes() + "분. ";
        if (current.destinationId().isBlank() || current.originKey().isBlank() || current.basis().isBlank()
            || !(current.basis().startsWith("ETA_") || current.basis().contains("ESTIMATE")))
            return new Advice(prefix + "같은 기준의 과거 예상 시간과 비교할 수 없습니다.", null, false, TrafficKind.NO_COMPARE, List.of());
        ZonedDateTime currentTime = Instant.ofEpochMilli(current.observedAt()).atZone(zone);
        int minute = currentTime.getHour() * 60 + currentTime.getMinute();
        // At most one estimate per local date, so refreshing a screen cannot inflate the baseline.
        Map<LocalDate, Estimate> days = new HashMap<>();
        for (Estimate old : history) {
            long age = current.observedAt() - old.observedAt();
            if (age <= 0 || age > RoutineRecommender.MAX_AGE_MILLIS || old.minutes() < 1
                || !current.destinationId().equals(old.destinationId())
                || !current.originKey().equals(old.originKey())
                || !current.basis().equals(old.basis()) || current.demo() != old.demo()) continue;
            ZonedDateTime oldTime = Instant.ofEpochMilli(old.observedAt()).atZone(zone);
            if (oldTime.toLocalDate().equals(currentTime.toLocalDate())
                || oldTime.getDayOfWeek() != currentTime.getDayOfWeek()
                || Math.abs(oldTime.getHour() * 60 + oldTime.getMinute() - minute) > 60) continue;
            Estimate previous = days.get(oldTime.toLocalDate());
            if (previous == null || old.observedAt() > previous.observedAt()) days.put(oldTime.toLocalDate(), old);
        }
        List<DayEstimate> compared = new ArrayList<>();
        days.entrySet().stream().sorted(Map.Entry.<LocalDate, Estimate>comparingByKey().reversed())
            .forEach(entry -> compared.add(new DayEstimate(entry.getKey(), entry.getValue().minutes())));
        if (days.size() < 3)
            return new Advice(prefix + "비교 가능한 과거 예상 시간 3일분이 아직 없습니다.", null, false, TrafficKind.NO_COMPARE, compared);
        List<Integer> minutes = new ArrayList<>();
        for (DayEstimate old : compared) minutes.add(old.minutes());
        Collections.sort(minutes);
        int middle = minutes.size() / 2;
        int median = minutes.size() % 2 == 1 ? minutes.get(middle)
            : (int)Math.round((minutes.get(middle - 1) + (long)minutes.get(middle)) / 2.0);
        int delay = current.minutes() - median;
        boolean important = delay >= 5 && delay >= median * 0.20;
        String difference = delay > 0 ? "과거 예상 시간 중앙값보다 " + delay + "분 더 걸릴 것으로 예상됩니다."
            : delay < 0 ? "과거 예상 시간 중앙값보다 " + (-delay) + "분 짧게 예상됩니다."
            : "과거 예상 시간 중앙값과 같습니다.";
        return new Advice(prefix + difference + (important ? " 출발 전 교통 상황을 확인해 주세요." : " 추가 경고는 생략합니다."),
            delay, important, important ? TrafficKind.LATE : TrafficKind.CALM, compared);
    }
}

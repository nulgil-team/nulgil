package kr.routine.core;
import java.util.*;
import java.time.*;
import kr.routine.core.Model.*;
public final class RoutineRecommender {
    public static final long MAX_AGE_MILLIS = 42L * 24 * 60 * 60 * 1000;
    /** Rules use local weekly time; observation timestamps are authoritative in the supplied zone. */
    public Recommendation recommend(List<Rule> rules,List<Selection> history,long now,ZoneId zone) {
        ZonedDateTime currentTime = Instant.ofEpochMilli(now).atZone(Objects.requireNonNull(zone, "zone"));
        int day = currentTime.getDayOfWeek().getValue();
        int minute = currentTime.getHour() * 60 + currentTime.getMinute();
        Map<String, Destination> registered = new LinkedHashMap<>();
        int closest = 61;
        for (Rule rule : rules) {
            int distance = Math.abs(rule.minute() - minute);
            if (rule.day() != day || distance > 60) continue;
            if (distance < closest) { registered.clear(); closest = distance; }
            if (distance == closest) registered.put(rule.destination().id(), rule.destination());
        }
        if (registered.size() == 1)
            return new Recommendation(registered.values().iterator().next(), "등록한 요일·시간대의 목적지입니다.", 0);
        if (registered.size() > 1) {
            List<TiedCandidate> candidates = new ArrayList<>();
            for (Destination destination : registered.values()) candidates.add(new TiedCandidate(destination, 0));
            return new Recommendation(null, "같은 시간대에 등록한 장소가 여러 곳입니다. 직접 골라 주세요.", 0,
                HoldType.TIE, candidates);
        }

        Map<String, Set<LocalDate>> dates = new LinkedHashMap<>();
        Map<String, Destination> destinations = new HashMap<>();
        for (Selection selection : history) {
            long age = now - selection.selectedAt();
            if (age < 0 || age > MAX_AGE_MILLIS) continue;
            ZonedDateTime selectedTime = Instant.ofEpochMilli(selection.selectedAt()).atZone(zone);
            // Stored day/minute can be stale after a zone change; never mix them with timestamp dates.
            if (selectedTime.getDayOfWeek().getValue() != day
                || Math.abs(selectedTime.getHour() * 60 + selectedTime.getMinute() - minute) > 60) continue;
            String id = selection.destination().id();
            // Repeated confirmations on one date cannot masquerade as a recurring habit.
            dates.computeIfAbsent(id, ignored -> new HashSet<>()).add(selectedTime.toLocalDate());
            destinations.put(id, selection.destination());
        }
        String winner = null;
        int best = 0;
        boolean tied = false;
        for (Map.Entry<String, Set<LocalDate>> entry : dates.entrySet()) {
            int count = entry.getValue().size();
            if (count > best) { winner = entry.getKey(); best = count; tied = false; }
            else if (count == best) tied = true;
        }
        if (best < 3)
            return new Recommendation(null, "추천 기록이 부족합니다. 평소 장소를 등록하거나 직접 골라 주세요.", best);
        if (tied) {
            List<TiedCandidate> candidates = new ArrayList<>();
            for (Map.Entry<String, Set<LocalDate>> entry : dates.entrySet())
                if (entry.getValue().size() == best)
                    candidates.add(new TiedCandidate(destinations.get(entry.getKey()), best));
            return new Recommendation(null, "이 시간대의 목적지 후보가 비슷합니다. 직접 골라 주세요.", best,
                HoldType.TIE, candidates);
        }
        return new Recommendation(destinations.get(winner), "최근 6주 중 같은 요일·시간대에 " + best + "일 선택한 장소입니다.", best);
    }
}

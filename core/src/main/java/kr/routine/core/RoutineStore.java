package kr.routine.core;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import kr.routine.core.Model.*;
public final class RoutineStore {
    private static final int MAX_HISTORY = 200;
    private record State(List<Rule> rules, List<Selection> selections, List<Estimate> estimates,
                         Preference preference, boolean enabled) {}
    private final Path path;
    private byte[] lastBytes;
    private State state;
    public RoutineStore(Path path) throws IOException {
        this.path = Objects.requireNonNull(path).toAbsolutePath();
        if (Files.exists(this.path)) {
            lastBytes = Files.readAllBytes(this.path);
            try { state = decode(lastBytes); }
            catch (RuntimeException | IOException error) {
                throw new IOException("저장 파일이 손상되었거나 지원하지 않는 형식입니다. 원본을 보존합니다.", error);
            }
        } else {
            state = new State(List.of(), List.of(), List.of(), Preference.RECOMMENDED, false);
        }
    }
    public synchronized List<Rule> rules(){return state.rules();}
    public synchronized List<Selection> selections(){return state.selections();}
    public synchronized List<Estimate> estimates(){return state.estimates();}
    public synchronized Preference preference(){return state.preference();}
    public synchronized boolean collectionEnabled(){return state.enabled();}
    public synchronized void putRule(Rule rule) throws IOException {
        Objects.requireNonNull(rule);
        List<Rule> rules = new ArrayList<>(state.rules());
        rules.removeIf(old -> old.day() == rule.day() && old.minute() == rule.minute());
        if (rules.size() >= 100) throw new IOException("등록 가능한 생활 패턴 수를 초과했습니다.");
        rules.add(rule);
        save(new State(List.copyOf(rules), state.selections(), state.estimates(), state.preference(), state.enabled()));
    }
    public synchronized void setPreference(Preference preference) throws IOException {
        save(new State(state.rules(), state.selections(), state.estimates(), Objects.requireNonNull(preference), state.enabled()));
    }
    public synchronized void setCollectionEnabled(boolean enabled) throws IOException {
        save(new State(state.rules(), state.selections(), state.estimates(), state.preference(), enabled));
    }
    public synchronized void recordSelection(Selection selection) throws IOException {
        Objects.requireNonNull(selection);
        if (!state.enabled()) return;
        for (Selection old : state.selections())
            if (old.selectedAt() == selection.selectedAt() && old.destination().id().equals(selection.destination().id())) return;
        List<Selection> rows = new ArrayList<>(state.selections()); rows.add(selection);
        rows.sort(Comparator.comparingLong(Selection::selectedAt));
        if (rows.size() > MAX_HISTORY) rows = new ArrayList<>(rows.subList(rows.size() - MAX_HISTORY, rows.size()));
        save(new State(state.rules(), List.copyOf(rows), state.estimates(), state.preference(), state.enabled()));
    }
    public synchronized void recordEstimate(Estimate estimate) throws IOException {
        Objects.requireNonNull(estimate);
        if (!state.enabled() || estimate.minutes() < 1) return;
        for (Estimate old : state.estimates())
            if (old.observedAt() == estimate.observedAt() && old.destinationId().equals(estimate.destinationId())
                && old.originKey().equals(estimate.originKey()) && old.basis().equals(estimate.basis()) && old.demo() == estimate.demo()) return;
        List<Estimate> rows = new ArrayList<>(state.estimates()); rows.add(estimate);
        rows.sort(Comparator.comparingLong(Estimate::observedAt));
        if (rows.size() > MAX_HISTORY) rows = new ArrayList<>(rows.subList(rows.size() - MAX_HISTORY, rows.size()));
        save(new State(state.rules(), state.selections(), List.copyOf(rows), state.preference(), state.enabled()));
    }
    /** User-invoked history removal; registered routines and preferences remain. */
    public synchronized void clearHistory() throws IOException {
        save(new State(state.rules(), List.of(), List.of(), state.preference(), state.enabled()));
    }
    /** One explicit demo seed is persisted together, so a failed write leaves no partial fixture. */
    synchronized void seedDemo(Rule rule, List<Selection> selections, List<Estimate> estimates) throws IOException {
        if (!state.enabled()) throw new IllegalStateException("모의 기록 저장에 먼저 동의해 주세요.");
        List<Rule> nextRules = new ArrayList<>(state.rules());
        if (rule != null) {
            nextRules.removeIf(old -> old.day() == rule.day() && old.minute() == rule.minute());
            if (nextRules.size() >= 100) throw new IOException("등록 가능한 생활 패턴 수를 초과했습니다.");
            nextRules.add(rule);
        }
        List<Selection> nextSelections = new ArrayList<>(state.selections());
        for (Selection row : selections) {
            boolean exists = nextSelections.stream().anyMatch(old -> old.selectedAt() == row.selectedAt()
                && old.destination().id().equals(row.destination().id()));
            if (!exists) nextSelections.add(row);
        }
        List<Estimate> nextEstimates = new ArrayList<>(state.estimates());
        for (Estimate row : estimates) {
            boolean exists = nextEstimates.stream().anyMatch(old -> old.observedAt() == row.observedAt()
                && old.destinationId().equals(row.destinationId()) && old.originKey().equals(row.originKey())
                && old.basis().equals(row.basis()) && old.demo() == row.demo());
            if (!exists) nextEstimates.add(row);
        }
        nextSelections.sort(Comparator.comparingLong(Selection::selectedAt));
        nextEstimates.sort(Comparator.comparingLong(Estimate::observedAt));
        if (nextSelections.size() > MAX_HISTORY) nextSelections = new ArrayList<>(nextSelections.subList(nextSelections.size() - MAX_HISTORY, nextSelections.size()));
        if (nextEstimates.size() > MAX_HISTORY) nextEstimates = new ArrayList<>(nextEstimates.subList(nextEstimates.size() - MAX_HISTORY, nextEstimates.size()));
        save(new State(List.copyOf(nextRules), List.copyOf(nextSelections), List.copyOf(nextEstimates), state.preference(), state.enabled()));
    }
    private void save(State next) throws IOException {
        byte[] current = Files.exists(path) ? Files.readAllBytes(path) : null;
        if (!Arrays.equals(current, lastBytes))
            throw new IOException("저장 파일이 외부에서 변경되었습니다. 덮어쓰지 않았습니다.");
        byte[] bytes = encode(next);
        Files.createDirectories(path.getParent());
        Path pending = Files.createTempFile(path.getParent(), ".routine-", ".pending");
        Files.write(pending, bytes, StandardOpenOption.WRITE);
        try { Files.move(pending, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException unsupported) { Files.move(pending, path, StandardCopyOption.REPLACE_EXISTING); }
        lastBytes = bytes;
        state = next;
    }
    private static byte[] encode(State value) throws IOException {
        Properties p = new Properties();
        p.setProperty("schema", "1"); p.setProperty("enabled", Boolean.toString(value.enabled()));
        p.setProperty("preference", value.preference().name());
        p.setProperty("rules", Integer.toString(value.rules().size()));
        p.setProperty("selections", Integer.toString(value.selections().size()));
        p.setProperty("estimates", Integer.toString(value.estimates().size()));
        for (int i=0;i<value.rules().size();i++) {
            Rule r=value.rules().get(i); String key="r."+i+".";
            destination(p,key,r.destination()); p.setProperty(key+"day",Integer.toString(r.day())); p.setProperty(key+"minute",Integer.toString(r.minute()));
        }
        for (int i=0;i<value.selections().size();i++) {
            Selection s=value.selections().get(i); String key="s."+i+".";
            destination(p,key,s.destination()); p.setProperty(key+"day",Integer.toString(s.day())); p.setProperty(key+"minute",Integer.toString(s.minute())); p.setProperty(key+"at",Long.toString(s.selectedAt()));
        }
        for (int i=0;i<value.estimates().size();i++) {
            Estimate e=value.estimates().get(i); String key="e."+i+".";
            p.setProperty(key+"id",e.destinationId()); p.setProperty(key+"origin",e.originKey()); p.setProperty(key+"basis",e.basis());
            p.setProperty(key+"minutes",Integer.toString(e.minutes())); p.setProperty(key+"at",Long.toString(e.observedAt()));
            p.setProperty(key+"details",e.details()); p.setProperty(key+"demo",Boolean.toString(e.demo()));
        }
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        try (Writer writer=new OutputStreamWriter(output,StandardCharsets.UTF_8)) {p.store(writer,"Routine prototype local settings");}
        return output.toByteArray();
    }
    private static State decode(byte[] bytes) throws IOException {
        Properties p=new Properties();
        try (Reader reader=new InputStreamReader(new ByteArrayInputStream(bytes),StandardCharsets.UTF_8)) {p.load(reader);}
        if (!"1".equals(required(p,"schema"))) throw new IOException("Unsupported schema");
        List<Rule> rules=new ArrayList<>(); List<Selection> selections=new ArrayList<>(); List<Estimate> estimates=new ArrayList<>();
        for(int i=0;i<count(p,"rules",100);i++) {String k="r."+i+".";rules.add(new Rule(destination(p,k),integer(p,k+"day"),integer(p,k+"minute")));}
        for(int i=0;i<count(p,"selections",MAX_HISTORY);i++) {String k="s."+i+".";selections.add(new Selection(destination(p,k),integer(p,k+"day"),integer(p,k+"minute"),Long.parseLong(required(p,k+"at"))));}
        for(int i=0;i<count(p,"estimates",MAX_HISTORY);i++) {String k="e."+i+".";estimates.add(new Estimate(required(p,k+"id"),required(p,k+"origin"),required(p,k+"basis"),integer(p,k+"minutes"),Long.parseLong(required(p,k+"at")),required(p,k+"details"),bool(p,k+"demo")));}
        return new State(List.copyOf(rules),List.copyOf(selections),List.copyOf(estimates),Preference.valueOf(required(p,"preference")),bool(p,"enabled"));
    }
    private static void destination(Properties p,String key,Destination d) {
        p.setProperty(key+"id",d.id());p.setProperty(key+"name",d.name());p.setProperty(key+"address",d.address());
        p.setProperty(key+"lat",Double.toString(d.latitude()));p.setProperty(key+"lon",Double.toString(d.longitude()));p.setProperty(key+"sub",d.subId());
    }
    private static Destination destination(Properties p,String key) {
        return new Destination(required(p,key+"id"),required(p,key+"name"),required(p,key+"address"),Double.parseDouble(required(p,key+"lat")),Double.parseDouble(required(p,key+"lon")),required(p,key+"sub"));
    }
    private static String required(Properties p,String key) {String v=p.getProperty(key);if(v==null)throw new IllegalArgumentException("Missing "+key);return v;}
    private static int integer(Properties p,String key) {return Integer.parseInt(required(p,key));}
    private static int count(Properties p,String key,int limit) {int n=integer(p,key);if(n<0||n>limit)throw new IllegalArgumentException("Invalid count "+key);return n;}
    private static boolean bool(Properties p,String key) {String v=required(p,key);if(!v.equals("true")&&!v.equals("false"))throw new IllegalArgumentException("Invalid boolean "+key);return Boolean.parseBoolean(v);}
}

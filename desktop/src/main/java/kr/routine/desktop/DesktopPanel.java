package kr.routine.desktop;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Path;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import kr.routine.core.*;
import kr.routine.core.Model.*;

/** The desktop is an explicitly simulated transport around the shared production-domain code. */
public final class DesktopPanel extends JPanel implements AutoCloseable {
    private static final Color INK = new Color(24, 48, 46);
    private static final Color MUTED = new Color(83, 105, 102);
    private static final Color GREEN = new Color(16, 102, 83);
    private static final Color BACKGROUND = new Color(239, 244, 241);
    private static final Color WARM = new Color(250, 241, 215);
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final String[] DAYS = {"월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일"};
    final RoutineStore store;
    final DemoNaviGateway gateway = new DemoNaviGateway();
    private DepartureController flow;
    private final RoutineRecommender recommender = new RoutineRecommender();
    private final TrafficAdvisor traffic = new TrafficAdvisor();
    private Recommendation recommendation;
    private Estimate estimate;
    private boolean estimateRecorded;
    private boolean refreshing;

    final JComboBox<String> day = new JComboBox<>(DAYS);
    final JTextField time = new JTextField("08:00");
    final JComboBox<String> scenario = new JComboBox<>(new String[]{"평소 수준 · 모의", "정체 발생 · 모의", "교통 정보 없음 · 모의"});
    final JComboBox<String> preference = new JComboBox<>(new String[]{"순정 추천", "빠른 경로", "편안한 경로"});
    final JComboBox<Destination> place = new JComboBox<>(DemoFixtures.destinations().toArray(new Destination[0]));
    final JCheckBox consent = new JCheckBox("선택 기록을 이 PC에 저장");
    final JCheckBox parked = new JCheckBox("정차 상태 · 모의", true);
    final JCheckBox historyOnly = new JCheckBox("선택 이력만으로 추천 시험");
    final JButton seedButton = new JButton("예시 이력 불러오기");
    final JButton ruleButton = new JButton("이 요일·시간의 평소 장소로 등록");
    final JButton refreshButton = new JButton("추천 다시 보기");
    final JButton newTripButton = new JButton("새 이동 시작 · 모의");
    final JButton confirmButton = new JButton("확인하고 안내 시작 · 모의");
    final JButton dismissButton = new JButton("이번에는 넘기기");
    final JButton clearButton = new JButton("선택·교통 이력 지우기");
    final JLabel destinationLabel = label("평소 장소를 알려주세요", 31, true);
    final JTextArea reasonText = textArea(3);
    final JLabel etaLabel = label("교통 정보 대기", 29, true);
    final JTextArea trafficText = textArea(4);
    final JTextArea statusText = textArea(3);
    final JLabel recordLabel = label("", 13, false);
    final JLabel contextLabel = label("", 14, false);
    private final JLabel stateLabel = label("준비", 13, true);

    public DesktopPanel(Path dataFile) throws IOException {
        super(new BorderLayout(0, 16));
        store = new RoutineStore(dataFile);
        flow = new DepartureController(gateway, store);
        setBackground(BACKGROUND);
        setBorder(new EmptyBorder(22, 26, 18, 26));
        day.setSelectedIndex(2);
        preference.setSelectedIndex(store.preference().ordinal());
        consent.setSelected(store.collectionEnabled());
        place.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                     boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof Destination d) setText(d.name());
                return this;
            }
        });
        add(header(), BorderLayout.NORTH);
        JPanel body = new JPanel(new BorderLayout(20, 0));
        body.setOpaque(false);
        body.add(controls(), BorderLayout.WEST);
        body.add(result(), BorderLayout.CENTER);
        add(body, BorderLayout.CENTER);
        JLabel footer = label("모의 교통 · 예시 장소 · 순정 내비 연동 미검증  |  실제 운행·위치 데이터는 수집하지 않습니다.", 13, false);
        add(footer, BorderLayout.SOUTH);
        wire();
        gateway.connect(new NaviGateway.Callback<Void>() {
            public void onSuccess(Void ignored) { refreshRecommendation(); }
            public void onFailure(String message) { status(message); }
        });
    }

    private JPanel header() {
        JPanel p = new JPanel(new BorderLayout(15, 4));
        p.setOpaque(false);
        p.add(label("늘 가는 길, 오늘은 어떨까요?", 28, true), BorderLayout.NORTH);
        JLabel badge = label("PC 프로토타입  ·  같은 추천 코드로 동작하는 시연", 14, true);
        badge.setOpaque(true); badge.setBackground(WARM);
        badge.setBorder(new EmptyBorder(10, 12, 10, 12));
        p.add(badge, BorderLayout.SOUTH);
        return p;
    }

    private JPanel controls() {
        JPanel p = new JPanel();
        p.setPreferredSize(new Dimension(330, 630));
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(Color.WHITE);
        p.setBorder(new EmptyBorder(18, 18, 18, 18));
        addField(p, "시연 요일 / 시간", pair(day, time));
        addField(p, "오늘의 교통 상황", scenario);
        addField(p, "안내 경로 선호", preference);
        addField(p, "평소 장소 등록 · 공개 예시 장소", place);
        addControl(p, ruleButton, 40);
        p.add(Box.createVerticalStrut(10));
        consent.setOpaque(false); parked.setOpaque(false); historyOnly.setOpaque(false);
        addControl(p, consent, 30);
        addControl(p, parked, 30);
        addControl(p, historyOnly, 30);
        p.add(Box.createVerticalStrut(6));
        addControl(p, seedButton, 40);
        p.add(Box.createVerticalStrut(6));
        addControl(p, clearButton, 38);
        p.add(Box.createVerticalStrut(12));
        JTextArea help = textArea(3);
        help.setText("예시 이력은 실제 운전 기록이 아닙니다.\n동의 후 예시를 불러오면 기록에 따른 추천과 지연 비교를 시험할 수 있습니다.");
        help.setFont(new Font("Malgun Gothic", Font.PLAIN, 12));
        help.setForeground(MUTED);
        addControl(p, help, 60);
        p.add(Box.createVerticalGlue());
        return p;
    }

    private JPanel result() {
        JPanel outer = new JPanel(new BorderLayout(0, 14));
        outer.setOpaque(false);
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(Color.WHITE);
        card.setBorder(new EmptyBorder(24, 28, 22, 28));
        addControl(card, contextLabel, 24);
        card.add(Box.createVerticalStrut(12));
        addControl(card, label("오늘의 목적지 제안", 14, false), 24);
        addControl(card, destinationLabel, 49);
        addControl(card, reasonText, 56);
        card.add(Box.createVerticalStrut(12));
        addControl(card, new JSeparator(), 1);
        card.add(Box.createVerticalStrut(16));
        addControl(card, etaLabel, 44);
        addControl(card, trafficText, 84);
        JLabel quoteNote = label("예상 시간은 경로 성향을 적용하기 전의 모의 값입니다.", 12, false);
        addControl(card, quoteNote, 25);
        card.add(Box.createVerticalStrut(14));
        addControl(card, stateLabel, 25);
        addControl(card, statusText, 55);
        card.add(Box.createVerticalGlue());
        addControl(card, recordLabel, 25);
        outer.add(card, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new GridLayout(2, 2, 10, 8));
        buttons.setOpaque(false);
        buttons.setPreferredSize(new Dimension(0, 96));
        confirmButton.setBackground(GREEN); confirmButton.setForeground(Color.WHITE);
        for (JButton b : new JButton[]{confirmButton, dismissButton, refreshButton, newTripButton}) {
            b.setFocusPainted(false); buttons.add(b);
        }
        outer.add(buttons, BorderLayout.SOUTH);
        return outer;
    }

    private void wire() {
        day.addActionListener(e -> refreshRecommendation());
        time.addActionListener(e -> refreshRecommendation());
        time.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusLost(java.awt.event.FocusEvent e) { refreshRecommendation(); }
        });
        scenario.addActionListener(e -> refreshTraffic());
        preference.addActionListener(e -> {
            try { store.setPreference(selectedPreference()); refreshRecommendation(); }
            catch (IOException ex) { status("선호 저장 실패: " + ex.getMessage()); }
        });
        consent.addActionListener(e -> {
            try { store.setCollectionEnabled(consent.isSelected()); updateState(); }
            catch (IOException ex) { consent.setSelected(store.collectionEnabled()); status("동의 설정 저장 실패: " + ex.getMessage()); }
        });
        parked.addActionListener(e -> { gateway.setParked(parked.isSelected()); updateState(); });
        historyOnly.addActionListener(e -> refreshRecommendation());
        ruleButton.addActionListener(e -> {
            try {
                store.putRule(new Rule((Destination) place.getSelectedItem(), dayValue(), minuteValue()));
                historyOnly.setSelected(false);
                refreshRecommendation(); status("평소 장소를 이 PC에 등록했습니다. 안내는 확인 후 시작됩니다.");
            } catch (IOException | IllegalArgumentException ex) { status("등록 실패: " + ex.getMessage()); }
        });
        seedButton.addActionListener(e -> {
            try {
                if (!store.collectionEnabled()) { status("예시 이력을 저장하려면 먼저 기록 저장에 동의해 주세요."); return; }
                DemoFixtures.seed(store, now(), ZONE);
                historyOnly.setSelected(true);
                refreshRecommendation(); status("예시 선택·예상 시간 이력을 불러왔습니다. 실제 주행 기록이 아닙니다.");
            } catch (IOException | IllegalArgumentException | IllegalStateException ex) { status("예시 불러오기 실패: " + ex.getMessage()); }
        });
        clearButton.addActionListener(e -> {
            try { store.clearHistory(); refreshRecommendation(); status("선택·교통 이력을 지웠습니다. 직접 등록한 장소 규칙은 유지됩니다."); }
            catch (IOException ex) { status("이력 삭제 실패: " + ex.getMessage()); }
        });
        refreshButton.addActionListener(e -> refreshRecommendation());
        newTripButton.addActionListener(e -> {
            gateway.resetRoute(); flow = new DepartureController(gateway, store);
            estimateRecorded = false; refreshRecommendation();
            status("새 모의 이동입니다. 실제 순정 내비에는 요청하지 않습니다.");
        });
        dismissButton.addActionListener(e -> { flow.dismiss(); updateState(); status(flow.message()); });
        confirmButton.addActionListener(e -> {
            try {
                Estimate chosenEstimate = estimate;
                flow.confirm(dayValue(), minuteValue(), now(), () -> {
                    if (flow.state() == DepartureController.State.VERIFIED && !estimateRecorded) {
                        estimateRecorded = true;
                        if (chosenEstimate != null && chosenEstimate.minutes() >= 0) {
                            try { store.recordEstimate(chosenEstimate); }
                            catch (IOException ex) { updateState(); status("모의 안내 확인. 예상 시간 저장 실패: " + ex.getMessage()); return; }
                        }
                    }
                    updateState(); status(flow.message());
                });
                updateState();
            } catch (IllegalArgumentException ex) { status(ex.getMessage()); }
        });
    }

    void refreshRecommendation() {
        if (refreshing) return;
        refreshing = true;
        try {
            contextLabel.setText("시연 시간  ·  " + DAYS[dayValue() - 1] + "  " + String.format("%02d:%02d", minuteValue() / 60, minuteValue() % 60));
            if (locked()) { status("이미 처리한 이동입니다. 새 조건은 ‘새 이동 시작 · 모의’에서 적용됩니다."); updateState(); return; }
            recommendation = recommender.recommend(historyOnly.isSelected() ? java.util.List.of() : store.rules(), store.selections(), now(), ZONE);
            if (recommendation.destination() == null) {
                flow = new DepartureController(gateway, store);
                destinationLabel.setText("추천을 아직 정하지 않았어요");
                reasonText.setText(recommendation.reason());
                estimate = null; etaLabel.setText("교통 정보 대기");
                trafficText.setText("평소 장소를 등록하거나, 동의 후 예시 이력을 불러오세요.");
            } else {
                flow.propose(recommendation.destination(), selectedPreference());
                destinationLabel.setText(recommendation.destination().name());
                reasonText.setText(recommendation.reason());
                refreshTraffic();
            }
            flow.refreshActiveRoute(() -> { updateState(); status(flow.message()); });
        } catch (IllegalArgumentException ex) { status("시간은 00:00~23:59 형식으로 입력해 주세요."); confirmButton.setEnabled(false); }
        finally { refreshing = false; }
    }

    void refreshTraffic() {
        if (recommendation == null || recommendation.destination() == null) return;
        try {
            gateway.setCongested(scenario.getSelectedIndex() == 1);
            gateway.setMissingTraffic(scenario.getSelectedIndex() == 2);
            gateway.estimate(recommendation.destination(), now(), new NaviGateway.Callback<Estimate>() {
                public void onSuccess(Estimate value) {
                    estimate = value;
                    Advice advice = traffic.advise(value, store.estimates(), now(), ZONE);
                    etaLabel.setText(value.minutes() >= 0 ? "현재 예상 " + value.minutes() + "분 · 모의" : "예상 시간을 확인할 수 없어요");
                    etaLabel.setForeground(advice.important() ? new Color(163, 85, 26) : INK);
                    trafficText.setText(advice.text() + "\n" + value.details());
                }
                public void onFailure(String message) {
                    estimate = null; etaLabel.setText("교통 정보 조회 실패"); trafficText.setText(message);
                }
            });
        } catch (IllegalArgumentException ex) { status("시연 시간을 확인해 주세요."); }
    }

    private boolean locked() {
        return flow.state() == DepartureController.State.CHECKING_ROUTE
            || flow.state() == DepartureController.State.REQUESTING
            || flow.state() == DepartureController.State.VERIFIED
            || flow.state() == DepartureController.State.UNCONFIRMED;
    }

    private void updateState() {
        boolean editable = !locked();
        for (JComponent component : new JComponent[]{day, time, preference, scenario, place, ruleButton,
                historyOnly, seedButton, refreshButton}) component.setEnabled(editable);
        confirmButton.setEnabled(flow.state() == DepartureController.State.READY
            && flow.activeRoute() == ActiveRoute.INACTIVE && gateway.isParked());
        dismissButton.setEnabled(flow.state() == DepartureController.State.READY);
        stateLabel.setText("처리 상태  ·  " + switch(flow.state()) {
            case IDLE -> "추천 준비"; case READY -> gateway.isParked() ? "사용자 확인 대기" : "주행 중 조작 제한 · 모의";
            case CHECKING_ROUTE -> "현재 경로 조회 중";
            case ALREADY_GUIDING -> "이미 안내 중";
            case ROUTE_UNAVAILABLE -> "현재 경로 조회 불가";
            case REQUESTING -> "확인 중"; case VERIFIED -> "모의 안내 확인 완료";
            case UNCONFIRMED -> "결과 미확인 · 재요청 차단"; case DISMISSED -> "이번 추천을 넘김";
        });
        recordLabel.setText("직접 등록 " + store.rules().size() + "건  ·  선택 기록 " + store.selections().size()
            + "건  ·  기록 저장 " + (store.collectionEnabled() ? "켜짐" : "꺼짐"));
    }

    private Preference selectedPreference() { return Preference.values()[preference.getSelectedIndex()]; }
    private int dayValue() { return day.getSelectedIndex() + 1; }
    private int minuteValue() {
        String[] tokens = time.getText().trim().split(":", -1);
        if (tokens.length != 2 || !tokens[0].matches("\\d{1,2}") || !tokens[1].matches("\\d{2}")) throw new IllegalArgumentException("잘못된 시간");
        int hour = Integer.parseInt(tokens[0]), minute = Integer.parseInt(tokens[1]);
        if (hour > 23 || minute > 59) throw new IllegalArgumentException("잘못된 시간");
        return hour * 60 + minute;
    }
    long now() {
        LocalDate monday = Instant.ofEpochMilli(DemoFixtures.NOW).atZone(ZONE).toLocalDate()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return monday.plusDays(dayValue() - 1).atTime(minuteValue() / 60, minuteValue() % 60)
            .atZone(ZONE).toInstant().toEpochMilli();
    }
    void status(String text) {
        statusText.setText(text == null ? "" : text.replace("COMFORTABLE", "편안한 경로")
            .replace("SHORT_TIME", "빠른 경로").replace("RECOMMENDED", "순정 추천"));
    }
    public void close() { gateway.close(); }

    private static JPanel pair(JComponent a, JComponent b) {
        JPanel p = new JPanel(new GridLayout(1, 2, 8, 0)); p.setOpaque(false); p.add(a); p.add(b); return p;
    }
    private static void addField(JPanel p, String text, JComponent control) {
        addControl(p, label(text, 13, true), 25); addControl(p, control, 38); p.add(Box.createVerticalStrut(10));
    }
    private static void addControl(JPanel p, JComponent c, int height) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
        c.setPreferredSize(new Dimension(100, height)); c.setMinimumSize(new Dimension(20, height));
        p.add(c);
    }
    private static JLabel label(String value, int size, boolean bold) {
        JLabel l = new JLabel(value); l.setFont(new Font("Malgun Gothic", bold ? Font.BOLD : Font.PLAIN, size));
        l.setForeground(bold ? INK : MUTED); return l;
    }
    private static JTextArea textArea(int rows) {
        JTextArea t = new JTextArea(rows, 0); t.setEditable(false); t.setFocusable(false);
        t.setLineWrap(true); t.setWrapStyleWord(true); t.setOpaque(false);
        t.setFont(new Font("Malgun Gothic", Font.PLAIN, 15)); t.setForeground(MUTED); return t;
    }
}

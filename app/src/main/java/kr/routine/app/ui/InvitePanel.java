package kr.routine.app.ui;

import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import kr.routine.app.vehicle.InviteUiState;
import kr.routine.core.Model.Destination;

/**
 * Friend stop screen (친구 들를 곳): invite code and QR, places friends sent, and what happened to them.
 * Layout and wording only. Decisions stay in VehicleSession; change this class to redesign the screen.
 */
public final class InvitePanel {
    public interface Actions {
        void openInvite();
        void closeInvite();
        /** Plan a friend's place with the destination (before departure). */
        void acceptSuggestion(String id);
        void dismissSuggestion(String id);
        void removePlannedStop(Destination stop);
        void refresh();
    }

    /** Things only the screen can do: confirm in a sheet before touching the route being guided. */
    public interface Host { void confirmAddNow(Destination stop); }

    private final UiKit kit;
    private final Actions actions;
    private final Host host;

    public InvitePanel(UiKit kit, Actions actions, Host host) { this.kit = kit; this.actions = actions; this.host = host; }

    /** Short entry button for other screens, with the number of places waiting. */
    public static String entryLabel(InviteUiState invite) {
        if (!invite.open()) return "친구에게 들를 곳 받기";
        return invite.waiting() > 0 ? "친구가 보낸 곳 " + invite.waiting() + "곳 확인" : "친구 초대 보기 · " + invite.code();
    }

    public void render(LinearLayout body, InviteUiState invite, boolean parked, ZoneId zone, java.util.List<MapMath.Marker> route, boolean night) {
        kit.heading(body, "친구에게 들를 곳 받기");
        if (!invite.message().isBlank()) kit.banner(body, invite.message(), true);
        if (!invite.open()) { closed(body, invite); return; }
        code(body, invite, zone);
        if (!invite.addHint().isBlank() && !invite.pending().isEmpty()) kit.banner(body, invite.addHint(), !parked);
        if (!invite.pending().isEmpty()) {
            java.util.List<MapMath.Marker> markers = new java.util.ArrayList<>(route);
            for (int i = 0; i < invite.pending().size(); i++) markers.add(RouteMapCard.friend(invite.pending().get(i).stop(), i));
            kit.add(body, RouteMapCard.build(kit, markers, night), 24);
        }
        pending(body, invite);
        handled(body, invite);
        LinearLayout buttons = kit.pair(kit.button("새로 확인", false, true, actions::refresh),
            kit.button("초대 끝내기", false, true, actions::closeInvite), 1, 1);
        kit.add(body, buttons, 28);
        kit.description(body, "초대를 끝내면 친구는 더 보낼 수 없어요. 이미 경로에 넣은 곳은 그대로예요.");
    }

    private void closed(LinearLayout body, InviteUiState invite) {
        kit.description(body, "친구가 코드로 가는 길에 들를 곳을 보내면, 정차 중에 확인하고 경로에 넣어요.");
        kit.description(body, "서버에는 초대 코드와 친구가 보낸 장소만 저장해요. 차의 위치·목적지·경로는 보내지 않아요. 코드는 12시간 뒤 끝나요.");
        kit.add(body, kit.button(invite.opening() ? "코드를 만드는 중" : "초대 코드 만들기", true,
            invite.canOpen(), actions::openInvite), 32);
    }

    private void code(LinearLayout body, InviteUiState invite, ZoneId zone) {
        LinearLayout box = kit.card();
        LinearLayout info = kit.column();
        kit.add(info, kit.text("초대 코드", 34, false, kit.colors.secondary()), 0);
        TextView code = kit.text(spaced(invite.code()), kit.metrics.compact() ? 84 : 120, true, kit.colors.text());
        code.setContentDescription("초대 코드 " + spaced(invite.code()));
        kit.add(info, code, 4);
        kit.add(info, kit.text(invite.url().isEmpty() ? "친구에게 이 코드를 알려 주세요."
            : "친구가 QR을 찍거나 아래 주소에서 코드를 넣으면 돼요.", 34, false, kit.colors.secondary()), 8);
        if (!invite.url().isEmpty()) kit.add(info, kit.text(invite.url(), 30, false, kit.colors.secondary()), 8);
        kit.add(info, kit.text(until(invite.expiresAt(), zone) + "까지 받을 수 있어요", 30, false, kit.colors.secondary()), 8);
        QrView qr = invite.url().isEmpty() ? null : new QrView(kit.activity, invite.url());
        if (qr != null && qr.valid()) {
            int size = kit.metrics.compact() ? 320 : 360;
            LinearLayout line = kit.metrics.compact() ? kit.column() : kit.row();
            line.addView(info, kit.metrics.compact() ? new LinearLayout.LayoutParams(UiKit.MATCH, UiKit.WRAP)
                : new LinearLayout.LayoutParams(0, UiKit.WRAP, 1));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            if (kit.metrics.compact()) { lp.topMargin = 20; lp.gravity = Gravity.CENTER_HORIZONTAL; } else lp.leftMargin = 32;
            line.addView(qr, lp);
            kit.add(box, line, 0);
        } else kit.add(box, info, 0);
        kit.add(body, box, 24);
    }

    private void pending(LinearLayout body, InviteUiState invite) {
        kit.add(body, kit.text(invite.pending().isEmpty() ? "아직 보낸 곳이 없어요" : "친구가 보낸 곳 " + invite.pending().size(),
            40, true, kit.colors.text()), 32);
        if (invite.pending().isEmpty()) { kit.description(body, "친구가 보내면 여기에 나타나요. 자동으로 확인해요."); return; }
        for (int index = 0; index < invite.pending().size(); index++) {
            InviteUiState.Item item = invite.pending().get(index);
            LinearLayout box = kit.card();
            kit.add(box, kit.text(RouteMapCard.friendLetter(index) + "  " + item.placeName(), 44, true, kit.colors.text()), 0);
            kit.add(box, kit.text(item.sender() + " 님 · " + item.address(), 32, false, kit.colors.secondary()), 6);
            if (!item.note().isBlank()) kit.add(box, kit.text("“" + item.note() + "”", 34, false, kit.colors.text()), 10);
            boolean planned = item.status().equals("PLANNED");
            if (planned) kit.add(box, kit.text("들를 곳으로 담았어요 · 안내를 시작할 때 함께 보내요", 32, true, kit.colors.selectedLine()), 12);
            kit.add(box, kit.pair(primary(item, invite, planned),
                kit.button("이번엔 넘기기", false, !planned || invite.addMode().equals("PLAN"), () -> actions.dismissSuggestion(item.id())), 2, 1), 16);
            kit.add(body, box, 14);
        }
    }

    private android.widget.Button primary(InviteUiState.Item item, InviteUiState invite, boolean planned) {
        if (planned) return kit.button("담은 곳에서 빼기", false, invite.addMode().equals("PLAN") || invite.addMode().equals("NONE"),
            () -> actions.removePlannedStop(item.stop()));
        return switch (invite.addMode()) {
            case "NOW" -> kit.button("경로에 넣기", true, true, () -> host.confirmAddNow(item.stop()));
            case "PLAN" -> kit.button("들를 곳으로 담기", true, true, () -> actions.acceptSuggestion(item.id()));
            default -> kit.button("지금은 넣을 수 없어요", true, false, () -> {});
        };
    }

    private void handled(LinearLayout body, InviteUiState invite) {
        if (invite.handled().isEmpty()) return;
        kit.add(body, kit.text("처리한 곳", 40, true, kit.colors.text()), 32);
        for (InviteUiState.Item item : invite.handled()) {
            TextView line = kit.text(item.placeName() + " · " + item.sender() + " 님\n" + statusLabel(item.status()), 34, false, kit.colors.text());
            line.setPadding(24, 18, 24, 18); line.setBackground(kit.shape(kit.colors.surface(), 0, 20));
            kit.add(body, line, 12);
        }
    }

    /** Car-side wording of a suggestion's status. The friend page has its own wording in invite-web/src/views.js. */
    public static String statusLabel(String status) {
        return switch (status) {
            case "PENDING" -> "기다리는 중";
            case "PLANNED" -> "들를 곳으로 담음";
            case "REQUESTED" -> "경로에 넣는 중";
            case "ADDED" -> "경로에 넣었어요";
            case "UNCONFIRMED" -> "들어갔는지 확인하지 못했어요 · 순정 내비를 확인해 주세요";
            case "FAILED" -> "넣지 못했어요";
            case "DISMISSED" -> "이번엔 넘겼어요";
            default -> status;
        };
    }

    private static String spaced(String code) {
        return code.length() == 6 ? code.substring(0, 3) + " " + code.substring(3) : code;
    }

    private static String until(long at, ZoneId zone) {
        ZonedDateTime time = Instant.ofEpochMilli(at).atZone(zone);
        int hour = time.getHour();
        boolean tomorrow = time.toLocalDate().isAfter(ZonedDateTime.now(zone).toLocalDate());
        return (tomorrow ? "내일 " : "") + (hour < 12 ? "오전 " : "오후 ") + (hour % 12 == 0 ? 12 : hour % 12) + ":" + String.format(java.util.Locale.ROOT, "%02d", time.getMinute());
    }
}

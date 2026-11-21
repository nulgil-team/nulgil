package kr.routine.phone;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.LinearLayout;
import java.util.List;
import kr.routine.core.NaviGateway.Callback;

/**
 * "공유 → 늘길": a place shared from KakaoMap, Naver Map, KakaoTalk or any text. Finds the place (resolve-place),
 * shows it on a map, then sends it to a friend's car (들를 곳, by invite code) or to my car (목적지, linked phone).
 * Links from 늘길 itself (invite code, the car's phone link) are saved instead.
 */
public final class ShareActivity extends Activity {
    private Ui ui;
    private PhoneStore store;
    private Server server;
    private String shared;
    private Place place;
    private List<Place> candidates = List.of();
    private String status = "LOADING", message = "", sentTo = "";
    private boolean sending;
    private EditText senderField, noteField;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        ui = new Ui(this); store = new PhoneStore(this); server = new Server(this);
        shared = textOf(getIntent());
        String host = getString(R.string.web_host);
        String code = Links.inviteCode(shared, host);
        Links.CarLink car = Links.carLink(shared, host);
        if (code != null || car != null) { // our own links: remember them, then show home
            startActivity(new Intent(this, MainActivity.class).setAction(Intent.ACTION_VIEW).putExtra(MainActivity.EXTRA_TEXT, shared));
            finish(); return;
        }
        render();
        server.resolve(shared, new Callback<>() {
            @Override public void onSuccess(Server.Resolved value) {
                candidates = value.candidates();
                if (value.status().equals("OK") && !candidates.isEmpty()) { place = candidates.get(0); status = "PLACE"; }
                else status = candidates.isEmpty() ? "NOT_FOUND" : "CHOOSE";
                render();
            }
            @Override public void onFailure(String error) { status = "NOT_FOUND"; message = Server.message(error); render(); }
        });
    }

    private static String textOf(Intent intent) {
        if (intent == null) return "";
        CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        String subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        String body = text == null ? "" : text.toString();
        return (subject != null && !body.contains(subject) ? subject + "\n" : "") + body;
    }

    private void render() {
        LinearLayout page = ui.page();
        ui.add(page, ui.brand(), 0);
        switch (status) {
            case "LOADING" -> { ui.add(page, ui.title("장소를 찾고 있어요"), 6); ui.add(page, ui.muted(preview()), 8); }
            case "NOT_FOUND" -> {
                ui.add(page, ui.title("장소를 찾지 못했어요"), 6);
                ui.add(page, ui.muted(message.isEmpty() ? "지도 앱의 공유 글이나 장소 이름을 보내 주세요." : message), 8);
                ui.add(page, ui.notice(preview(), false), 16);
                ui.add(page, ui.button("닫기", false, this::finish), 20);
            }
            case "CHOOSE" -> {
                ui.add(page, ui.title("어느 곳인가요?"), 6);
                ui.add(page, ui.muted("공유한 글로 찾은 곳이 여러 개예요."), 8);
                for (Place p : candidates)
                    ui.add(page, ui.button(p.name() + "\n" + p.address(), false, () -> { place = p; status = "PLACE"; render(); }), 10);
            }
            case "SENT" -> {
                ui.add(page, ui.title("보냈어요"), 6);
                ui.add(page, ui.muted(sentTo), 8);
                placeCard(page);
                ui.add(page, ui.button("닫기", true, this::finish), 20);
            }
            default -> placeScreen(page);
        }
    }

    private void placeScreen(LinearLayout page) {
        ui.add(page, ui.title("이 곳을 차로 보낼까요?"), 6);
        placeCard(page);
        if (!message.isEmpty()) ui.add(page, ui.notice(message, true), 12);
        List<String> invites = store.invites();
        Links.CarLink car = store.car();
        if (invites.isEmpty() && car == null) {
            ui.add(page, ui.notice("보낼 곳이 없어요. 친구가 카톡으로 보낸 늘길 초대 링크를 열거나, 차 화면 내 일상 → 폰 연결의 QR을 이 폰으로 찍어 주세요.", true), 16);
            ui.add(page, ui.button("늘길 열기", false, () -> { startActivity(new Intent(this, MainActivity.class)); finish(); }), 12);
            return;
        }
        if (car != null)
            ui.add(page, ui.button(sending ? "보내는 중" : "내 차 목적지로 보내기", true, () -> sendToCar(car)), 20);
        if (!invites.isEmpty()) {
            LinearLayout friend = ui.card();
            ui.add(friend, ui.text("친구 차에 들를 곳으로", 16, true, ui.text), 0);
            senderField = ui.field("내 이름 (예: 민지)", store.sender(), 20);
            ui.add(friend, senderField, 8);
            noteField = ui.field("한마디 (선택, 예: 빵 사 갈게)", "", 80);
            ui.add(friend, noteField, 4);
            for (String code : invites)
                ui.add(friend, ui.button(sending ? "보내는 중" : "초대 " + spaced(code) + " 차로 보내기", car == null, () -> sendToInvite(code)), 10);
            ui.add(page, friend, 16);
        }
        ui.add(page, ui.button("취소", false, this::finish), 12);
    }

    private void placeCard(LinearLayout page) {
        LinearLayout card = ui.card();
        ui.add(card, ui.muted(place.sourceLabel()), 0);
        ui.add(card, ui.text(place.name(), 20, true, ui.text), 4);
        if (!place.address().isBlank()) ui.add(card, ui.muted(place.address()), 2);
        PlaceMapView map = new PlaceMapView(this); map.show(place);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.MATCH, ui.dp(200)); lp.topMargin = ui.dp(12);
        card.addView(map, lp);
        ui.add(page, card, 16);
    }

    private void sendToInvite(String code) {
        if (sending) return;
        String sender = senderField == null ? "" : senderField.getText().toString().trim();
        if (sender.isEmpty()) { message = "내 이름을 적어 주세요. 운전자 화면에 보여요."; render(); return; }
        store.setSender(sender);
        String note = noteField == null ? "" : noteField.getText().toString().trim();
        sending = true; message = ""; render();
        server.sendToInvite(code, sender, place, note, new Callback<>() {
            @Override public void onSuccess(String id) {
                sending = false; status = "SENT";
                sentTo = "초대 " + spaced(code) + " 차에 들를 곳으로 보냈어요. 운전자 화면에 \"경유지가 도착했어요\"로 떠요.";
                render();
            }
            @Override public void onFailure(String error) {
                sending = false; message = Server.message(error);
                if (error != null && error.startsWith("TRIP_CLOSED")) store.removeInvite(code);
                render();
            }
        });
    }

    private void sendToCar(Links.CarLink car) {
        if (sending) return;
        sending = true; message = ""; render();
        server.sendToCar(car, place, new Callback<>() {
            @Override public void onSuccess(String id) {
                sending = false; status = "SENT";
                sentTo = "내 차에 목적지로 보냈어요. 차에 타서 늘길을 열면 \"폰에서 보낸 경로예요\"로 떠요. 안내 시작만 누르면 돼요.";
                render();
            }
            @Override public void onFailure(String error) {
                sending = false; message = Server.message(error);
                if (error != null && error.startsWith("NOT_ALLOWED")) store.setCar(null);
                render();
            }
        });
    }

    private String preview() { String t = shared == null ? "" : shared.trim(); return t.length() > 200 ? t.substring(0, 200) + "…" : t; }
    static String spaced(String code) { return code.length() == 6 ? code.substring(0, 3) + " " + code.substring(3) : code; }
}

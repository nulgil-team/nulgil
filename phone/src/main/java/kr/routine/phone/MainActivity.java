package kr.routine.phone;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.LinearLayout;
import kr.routine.core.NaviGateway.Callback;

/**
 * Home of the phone app: how to share, where places go (friends' invite codes, my car) and the copied-link shortcut.
 * Also opens 늘길's own links: a friend's invite link (?c=코드) and the car's phone link (phone.html?l=..&k=..).
 */
public final class MainActivity extends Activity {
    static final String EXTRA_TEXT = "kr.routine.phone.TEXT";
    private Ui ui;
    private PhoneStore store;
    private Server server;
    private String notice = "", clipboard = "";

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        ui = new Ui(this); store = new PhoneStore(this); server = new Server(this);
        handle(getIntent());
        render();
    }

    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handle(intent); render(); }

    /** The clipboard can only be read while the app has focus (Android 10+). */
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) return;
        String text = readClipboard();
        String next = Links.looksLikePlace(text, getString(R.string.web_host)) && text.matches("(?s).*https?://.*") ? text : "";
        if (!next.equals(clipboard)) { clipboard = next; render(); }
    }

    private void handle(Intent intent) {
        if (intent == null) return;
        Uri data = intent.getData();
        String text = data != null ? data.toString() : intent.getStringExtra(EXTRA_TEXT);
        if (text == null) return;
        String host = getString(R.string.web_host);
        Links.CarLink car = Links.carLink(text, host);
        String code = Links.inviteCode(text, host);
        if (car != null) { store.setCar(car); notice = "내 차와 연결했어요. 이제 지도 앱에서 공유하면 내 차 목적지로 보낼 수 있어요."; }
        else if (code != null) addInvite(code);
    }

    private void addInvite(String code) {
        store.addInvite(code);
        notice = "초대 " + ShareActivity.spaced(code) + "를 저장했어요. 지도 앱에서 공유하면 이 친구 차로 보낼 수 있어요.";
        server.inviteOpen(code, new Callback<>() {
            @Override public void onSuccess(Boolean open) {
                if (!open) { store.removeInvite(code); notice = "초대 " + ShareActivity.spaced(code) + "는 이미 끝났어요."; render(); }
            }
            @Override public void onFailure(String error) {}
        });
    }

    private void render() {
        LinearLayout page = ui.page();
        ui.add(page, ui.brand(), 0);
        ui.add(page, ui.title("가는 길에 들를 곳, 지도에서 공유하세요"), 6);
        ui.add(page, ui.muted("카카오맵·네이버지도에서 장소를 열고 공유 → 늘길을 누르면, 친구 차나 내 차로 바로 보낼 수 있어요."), 8);
        if (!notice.isEmpty()) ui.add(page, ui.notice(notice, false), 16);
        if (!clipboard.isEmpty())
            ui.add(page, ui.button("복사한 장소 보내기", true, () -> startActivity(new Intent(this, ShareActivity.class)
                .setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, clipboard))), 16);

        LinearLayout friends = ui.card();
        ui.add(friends, ui.text("친구 차 (초대 코드)", 16, true, ui.text), 0);
        if (store.invites().isEmpty()) ui.add(friends, ui.muted("친구가 카톡으로 보낸 늘길 초대 링크를 열면 여기에 생겨요. 코드를 직접 넣어도 돼요."), 6);
        for (String code : store.invites()) {
            LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(ui.text("초대 " + ShareActivity.spaced(code), 16, false, ui.text), new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
            row.addView(ui.button("지우기", false, () -> { store.removeInvite(code); render(); }));
            ui.add(friends, row, 8);
        }
        EditText codeField = ui.field("초대 코드 6자리", "", 7);
        ui.add(friends, codeField, 8);
        ui.add(friends, ui.button("코드 추가", false, () -> {
            String code = Links.inviteCode(codeField.getText().toString(), getString(R.string.web_host));
            if (code == null) notice = "6자리 초대 코드를 확인해 주세요."; else addInvite(code);
            render();
        }), 6);
        ui.add(page, friends, 16);

        LinearLayout car = ui.card();
        ui.add(car, ui.text("내 차", 16, true, ui.text), 0);
        if (store.car() == null) ui.add(car, ui.muted("차 화면 내 일상 → 폰 연결의 QR을 이 폰 카메라로 찍으면 연결돼요."), 6);
        else {
            ui.add(car, ui.muted("연결돼 있어요. 공유한 장소를 내 차 목적지로 보낼 수 있어요."), 6);
            ui.add(car, ui.button("이 폰에서 연결 지우기", false, () -> { store.setCar(null); notice = "이 폰에서 차 연결을 지웠어요."; render(); }), 8);
        }
        ui.add(page, car, 12);
        ui.add(page, ui.muted("보낸 장소와 이름만 서버에 잠시 저장돼요. 이 폰의 위치는 보내지 않아요."), 16);
    }

    private String readClipboard() {
        try {
            ClipboardManager cm = getSystemService(ClipboardManager.class);
            ClipData clip = cm == null ? null : cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return "";
            CharSequence text = clip.getItemAt(0).coerceToText(this);
            return text == null ? "" : text.toString();
        } catch (RuntimeException denied) { return ""; }
    }
}

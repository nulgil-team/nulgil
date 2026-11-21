package kr.routine.app.vehicle;

/**
 * 폰에서 경로 보내기 as the screen sees it. pairUrl is set only right after pairing (the QR); waitingName is the place
 * the phone sent, if any.
 */
public record PhoneUiState(boolean available, boolean linked, boolean pairing, String pairUrl, String message, String waitingName) {
    public PhoneUiState {
        pairUrl = pairUrl == null ? "" : pairUrl;
        message = message == null ? "" : message;
        waitingName = waitingName == null ? "" : waitingName;
    }
    public static PhoneUiState unavailable() { return new PhoneUiState(false, false, false, "", "", ""); }
}

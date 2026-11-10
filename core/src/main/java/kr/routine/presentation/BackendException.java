package kr.routine.presentation;

/** Stable machine-readable command error; UI text is separate from the code. */
public final class BackendException extends RuntimeException {
    private final String code;
    public BackendException(String code, String message) { super(message); this.code = code; }
    public BackendException(String code, String message, Throwable cause) { super(message, cause); this.code = code; }
    public String code() { return code; }
}

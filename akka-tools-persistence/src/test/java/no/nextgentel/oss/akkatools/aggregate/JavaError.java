package no.nextgentel.oss.akkatools.aggregate;

public class JavaError extends AggregateError {

    public JavaError(String errorMsg) {
        super(errorMsg, false);
    }

    public JavaError(String errorMsg, boolean skipErrorHandler) {
        super(errorMsg, skipErrorHandler);
    }
}

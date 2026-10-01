package net.minecraft.bootstrap;

public class FatalBootstrapError extends RuntimeException {
    public FatalBootstrapError(String reason) {
        super(reason);
    }

    public FatalBootstrapError(String reason, Throwable cause) {
        super(reason, cause);
    }
}

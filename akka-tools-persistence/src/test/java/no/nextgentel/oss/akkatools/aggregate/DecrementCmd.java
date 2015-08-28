package no.nextgentel.oss.akkatools.aggregate;

import java.io.Serializable;

public class DecrementCmd implements AggregateCmd, Serializable {

    private final String id;

    public DecrementCmd(String id) {
        this.id = id;
    }

    @Override
    public String id() {
        return id;
    }
}

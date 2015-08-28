package no.nextgentel.oss.akkatools.aggregate;

import java.io.Serializable;

public class IncrementCmd implements AggregateCmd, Serializable{

    private final String id;

    public IncrementCmd(String id) {
        this.id = id;
    }

    @Override
    public String id() {
        return id;
    }
}

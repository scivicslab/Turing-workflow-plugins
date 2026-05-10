package com.scivicslab.turingworkflow.plugins.logoutput;

import com.scivicslab.pojoactor.core.accumulator.Accumulator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MultiplexerAccumulatorTest {

    private MultiplexerAccumulator mux;

    @BeforeEach
    void setUp() {
        mux = new MultiplexerAccumulator();
    }

    private static Accumulator capturingTarget(List<String> received) {
        return new Accumulator() {
            @Override public void add(String source, String type, String data) {
                received.add(source + ":" + type + ":" + data);
            }
            @Override public String getSummary() { return "capturing"; }
            @Override public int getCount() { return received.size(); }
            @Override public void clear() { received.clear(); }
        };
    }

    private static Accumulator dataTarget(List<String> data) {
        return new Accumulator() {
            @Override public void add(String s, String t, String d) { data.add(d); }
            @Override public String getSummary() { return "data"; }
            @Override public int getCount() { return data.size(); }
            @Override public void clear() { data.clear(); }
        };
    }

    @Test
    void initialCount_isZero() {
        assertThat(mux.getCount()).isZero();
    }

    @Test
    void add_incrementsCount() {
        mux.add("node-01", "stdout", "Hello");
        mux.add("node-01", "stdout", "World");
        assertThat(mux.getCount()).isEqualTo(2);
    }

    @Test
    void add_forwardsToRegisteredTarget() {
        List<String> received = new ArrayList<>();
        mux.addTarget(capturingTarget(received));

        mux.add("node-01", "stdout", "output text");

        assertThat(received).containsExactly("node-01:stdout:output text");
    }

    @Test
    void add_forwardsToAllTargets() {
        List<String> t1 = new ArrayList<>();
        List<String> t2 = new ArrayList<>();
        mux.addTarget(dataTarget(t1));
        mux.addTarget(dataTarget(t2));

        mux.add("node", "stdout", "data");

        assertThat(t1).containsExactly("data");
        assertThat(t2).containsExactly("data");
    }

    @Test
    void addTarget_null_isIgnored() {
        mux.addTarget(null);
        assertThat(mux.getTargetCount()).isZero();
    }

    @Test
    void removeTarget_returnsTrue_whenFound() {
        List<String> log = new ArrayList<>();
        Accumulator target = dataTarget(log);
        mux.addTarget(target);

        boolean removed = mux.removeTarget(target);

        assertThat(removed).isTrue();
        assertThat(mux.getTargetCount()).isZero();
    }

    @Test
    void removeTarget_returnsFalse_whenNotFound() {
        List<String> log = new ArrayList<>();
        assertThat(mux.removeTarget(dataTarget(log))).isFalse();
    }

    @Test
    void clear_resetsCount() {
        mux.add("node", "stdout", "a");
        mux.add("node", "stdout", "b");
        mux.clear();
        assertThat(mux.getCount()).isZero();
    }

    @Test
    void clear_propagatesToTargets() {
        int[] clearCount = {0};
        Accumulator target = new Accumulator() {
            @Override public void add(String s, String t, String d) {}
            @Override public String getSummary() { return ""; }
            @Override public int getCount() { return 0; }
            @Override public void clear() { clearCount[0]++; }
        };
        mux.addTarget(target);

        mux.clear();

        assertThat(clearCount[0]).isEqualTo(1);
    }

    @Test
    void getSummary_containsCount() {
        mux.add("node", "stdout", "x");
        mux.add("node", "stdout", "y");
        String summary = mux.getSummary();
        assertThat(summary).contains("2 entries forwarded");
    }

    @Test
    void getSummary_containsTargetCount() {
        List<String> dummy = new ArrayList<>();
        mux.addTarget(dataTarget(dummy));
        mux.addTarget(dataTarget(dummy));
        String summary = mux.getSummary();
        assertThat(summary).contains("2 targets");
    }

    @Test
    void targetException_doesNotPreventOtherTargets() {
        List<String> received = new ArrayList<>();
        Accumulator failing = new Accumulator() {
            @Override public void add(String s, String t, String d) { throw new RuntimeException("failing"); }
            @Override public String getSummary() { return ""; }
            @Override public int getCount() { return 0; }
            @Override public void clear() {}
        };
        mux.addTarget(failing);
        mux.addTarget(dataTarget(received));

        mux.add("node", "stdout", "data");

        assertThat(received).containsExactly("data");
        assertThat(mux.getCount()).isEqualTo(1);
    }
}

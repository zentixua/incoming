import java.lang.instrument.Instrumentation;
public final class Agent {
    public static Instrumentation instrumentation;
    public static void premain(String args, Instrumentation value) { instrumentation = value; }
}

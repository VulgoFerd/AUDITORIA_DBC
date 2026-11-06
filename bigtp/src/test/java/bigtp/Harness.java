package bigtp;

import java.util.ArrayList;
import java.util.List;

/** Harness minimo, sem dependencias externas. */
public final class Harness {

    private int passed;
    private int failed;
    private final List<String> failures = new ArrayList<String>();

    public void section(String name) {
        System.out.println();
        System.out.println("== " + name + " ==");
    }

    public void truth(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  OK   " + name);
        } else {
            fail(name, "esperado true, obtido false");
        }
    }

    public void eq(String name, long expected, long actual) {
        if (expected == actual) {
            passed++;
            System.out.println("  OK   " + name + " = " + actual);
        } else {
            fail(name, "esperado " + expected + ", obtido " + actual);
        }
    }

    public void eq(String name, Object expected, Object actual) {
        boolean equal = expected == null ? actual == null : expected.equals(actual);
        if (equal) {
            passed++;
            System.out.println("  OK   " + name + " = " + actual);
        } else {
            fail(name, "esperado " + expected + ", obtido " + actual);
        }
    }

    private void fail(String name, String detail) {
        failed++;
        failures.add(name + ": " + detail);
        System.out.println("  FAIL " + name + " (" + detail + ")");
    }

    public int summary() {
        System.out.println();
        System.out.println("---------------------------------------------");
        System.out.println("passou: " + passed + " | falhou: " + failed);
        if (failed > 0) {
            System.out.println("FALHAS:");
            for (String f : failures) {
                System.out.println("  - " + f);
            }
            System.out.println("RESULTADO: FALHOU");
            return 1;
        }
        System.out.println("RESULTADO: OK");
        return 0;
    }
}

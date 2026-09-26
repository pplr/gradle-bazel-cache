package demo;

/** Just enough code to give the compiler something cacheable to do. */
public final class Demo {
    private Demo() {}

    public static String greet(String name) {
        return "Hello, " + name + "!";
    }

    public static void main(String[] args) {
        System.out.println(greet(args.length > 0 ? args[0] : "world"));
    }
}


public class BrokenAlignment {
  private static void foo(long value) {
    if (value == (byte)value) {
      System.out.println("1");
    } else if (value == (short)value) {
      System.out.println("2");
    } else if (value == (int)value) {
      System.out.println("3");
    } else {
      System.out.println("4");
    }
  }

}
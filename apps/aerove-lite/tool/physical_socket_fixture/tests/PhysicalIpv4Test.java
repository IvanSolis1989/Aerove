package dev.aerove.fixture.socketowner;
public final class PhysicalIpv4Test {
    public static void main(String[] args) {
        for (String host : new String[]{"10.1.2.3","172.16.0.1","172.31.0.1","192.168.50.2"})
            if (PhysicalSocketService.privateIpv4(host).length != 4) throw new AssertionError(host);
        for (String host : new String[]{"10.0.2.2","172.15.0.1","172.32.0.1","192.169.1.1","192.168.050.2",
            "192.168.50.256","192.168.50","localhost","8.8.8.8","0.0.0.0","127.0.0.1","::ffff:192.168.50.2"}) {
            try { PhysicalSocketService.privateIpv4(host); }
            catch (IllegalArgumentException expected) { continue; }
            throw new AssertionError(host);
        }
        System.out.println("16 literal-address cases passed");
    }
}

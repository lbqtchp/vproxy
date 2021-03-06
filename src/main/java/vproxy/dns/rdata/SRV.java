package vproxy.dns.rdata;

import vproxy.dns.DNSType;
import vproxy.dns.Formatter;
import vproxy.dns.InvalidDNSPacketException;
import vproxy.util.ByteArray;

public class SRV implements RData {
    public int priority;
    public int weight;
    public int port;
    public String target;

    @Override
    public ByteArray toByteArray() {
        ByteArray priority_weight_port = ByteArray.allocate(6);
        priority_weight_port.int16(0, priority).int16(2, weight).int16(4, port);
        return priority_weight_port.concat(Formatter.formatDomainName(target));
    }

    @Override
    public DNSType type() {
        return DNSType.SRV;
    }

    @Override
    public void fromByteArray(ByteArray data, ByteArray rawPacket) throws InvalidDNSPacketException {
        priority = rawPacket.uint16(0);
        weight = rawPacket.uint16(2);
        port = rawPacket.uint16(4);
        int[] offsetHolder = {0};
        target = Formatter.parseDomainName(data.sub(6, data.length() - 6), rawPacket, offsetHolder);
        if (offsetHolder[0] != data.length()) {
            throw new InvalidDNSPacketException("more bytes readable in the srv rdata field: " + this + ", data.len=" + data.length());
        }
    }

    @Override
    public String toString() {
        return "SRV{" +
            "priority=" + priority +
            ", weight=" + weight +
            ", port=" + port +
            ", target='" + target + '\'' +
            '}';
    }
}

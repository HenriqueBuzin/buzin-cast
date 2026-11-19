// Exercise the production packet codec without advertising on the host LAN.
#include "../kepler/turbo-modules/MdnsDiscovery.cpp"
#include <iostream>

void check(bool result) {
  if (!result) throw std::runtime_error("DNS-SD packet assertion failed");
}
int main() {
  const std::string instance = "Buzin Cast-test._fcast._tcp.local";
  const MdnsDiscovery::Service fcast{"Buzin Cast-test", "_fcast._tcp.local", 46899, {3, 'v', '=', '2'}};
  const std::string host = "buzincast-test.local";
  auto query = [](const std::string& owner, uint16_t type) {
    std::vector<uint8_t> packet(12, 0);
    packet[5] = 1;
    name(packet, owner); u16(packet, type); u16(packet, 1);
    return packet;
  };
  auto asks = [&](const std::vector<uint8_t>& packet) {
    return asksForService(packet.data(), packet.size(), fcast, host);
  };
  auto packet = query("_FCAST._TCP.LOCAL", 12);
  check(asks(packet));
  for (size_t n = 0; n < packet.size(); ++n) {
    check(!asks(std::vector<uint8_t>(packet.begin(), packet.begin() + n)));
  }
  check(asks(query(instance, 33)));
  check(asks(query(instance, 16)));
  check(asks(query(host, 1)));
  check(!asks(query(host, 28)));
  check(!asks(query("_fcast._tcp.local.evil", 12)));
  packet = query("unrelated.local", 12);
  name(packet, "_fcast._tcp.local"); // Text in a payload is not a question.
  check(!asks(packet));
  packet = query("_fcast._tcp.local", 12);
  packet[5] = 2;
  packet.push_back(0xc0); packet.push_back(12); u16(packet, 12); u16(packet, 1);
  check(asks(packet));
  packet[packet.size() - 5] = static_cast<uint8_t>(packet.size() - 6);
  check(!asks(packet)); // self-referencing compression pointer
  const auto response = announcement(fcast, host, htonl(0xc0000201), 120);
  check(!asks(response));
  check(response[7] == 4);
  size_t offset = 12;
  const int types[] = {12, 33, 16, 1};
  for (int type : types) {
    std::string owner;
    check(dnsName(response.data(), response.size(), offset, owner));
    check(offset + 10 <= response.size());
    check(((response[offset] << 8) | response[offset + 1]) == type);
    const size_t size = (response[offset + 8] << 8) | response[offset + 9];
    offset += 10;
    check(offset + size <= response.size());
    if (type == 33) check(((response[offset + 4] << 8) | response[offset + 5]) == 46899);
    if (type == 1) check(size == 4 && response[offset] == 192 && response[offset + 3] == 1);
    offset += size;
  }
  check(offset == response.size());
  const MdnsDiscovery::Service airplay{"Buzin Cast", "_airplay._tcp.local", 42300, {3, 'x', '=', '1'}};
  packet = query("_airplay._tcp.local", 12);
  check(!asks(packet));
  check(asksForService(packet.data(), packet.size(), airplay, host));
  const auto goodbye = announcement(airplay, host, htonl(0xc0000201), 0);
  offset = 12;
  std::string owner;
  check(dnsName(goodbye.data(), goodbye.size(), offset, owner) && owner == airplay.type);
  check(goodbye[offset + 4] == 0 && goodbye[offset + 5] == 0 &&
        goodbye[offset + 6] == 0 && goodbye[offset + 7] == 0);
  std::cout << "DNS-SD queries, compression, truncation and PTR/SRV/TXT/A records passed\n";
}

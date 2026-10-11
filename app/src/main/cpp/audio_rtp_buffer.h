#ifndef AUDIO_RTP_BUFFER_H
#define AUDIO_RTP_BUFFER_H

#include <array>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace airplay {

// Reorders audio RTP packets by sequence number (modelled on UxPlay's raop_buffer.c):
// late and duplicate packets are dropped, a jump of a whole window resets the buffer, and gaps are
// reported so the receiver can ask the sender to retransmit them.
// Unlike UxPlay, which waits until the window is full, a missing packet is only awaited for a
// bounded time because playback here is not delayed by a latency buffer.
class AudioRtpBuffer {
public:
    static constexpr size_t kLength = 256;

    struct Packet {
        uint16_t seq{0};
        uint32_t rtpTime{0};
        std::vector<uint8_t> payload;
    };

    void reset();

    // Stores a decrypted payload (length 0 for filler packets, which are returned with an empty
    // payload so they never look like losses). Returns false when the packet is late or a duplicate.
    bool enqueue(uint16_t seq, uint32_t rtpTime, const uint8_t* payload, size_t length);

    // Pops the next packet in sequence order. A missing packet is skipped once it has been awaited
    // for maxWaitNs (or the window is full). Returns false when nothing can be played yet.
    bool dequeue(int64_t nowNs, int64_t maxWaitNs, Packet& out);

    // Reports the first gap ([seq, seq + count)) that has not been requested yet.
    bool takeResendRequest(uint16_t& seq, uint16_t& count);

    // Packets lost for good (skipped after waiting) since the last reset.
    uint64_t lostPackets() const { return lost_; }

private:
    struct Slot {
        bool filled{false};
        Packet packet;
    };

    static int16_t seqDiff(uint16_t a, uint16_t b) { return static_cast<int16_t>(static_cast<uint16_t>(a - b)); }

    std::array<Slot, kLength> slots_{};
    bool empty_{true};
    uint16_t first_{0};
    uint16_t last_{0};
    int64_t waitingSinceNs_{0};
    bool gapRequested_{false};
    uint64_t lost_{0};
};

} // namespace airplay

#endif // AUDIO_RTP_BUFFER_H

#include "audio_rtp_buffer.h"
#include <utility>

namespace airplay {

void AudioRtpBuffer::reset() {
    for (auto& slot : slots_) {
        slot.filled = false;
        slot.packet.payload.clear();
    }
    empty_ = true;
    waitingSinceNs_ = 0;
    gapRequested_ = false;
    lost_ = 0;
}

bool AudioRtpBuffer::enqueue(uint16_t seq, uint32_t rtpTime, const uint8_t* payload, size_t length) {
    if (empty_) {
        first_ = seq;
        last_ = static_cast<uint16_t>(seq - 1);
        empty_ = false;
    } else {
        int16_t fromFirst = seqDiff(seq, first_);
        if (fromFirst < 0 && fromFirst >= -static_cast<int>(kLength)) {
            return false; // Already played or skipped.
        }
        if (fromFirst < 0 || static_cast<size_t>(fromFirst) >= kLength) {
            // The sender jumped more than a window away (pause, restarted sequence): restart here.
            for (auto& slot : slots_) slot.filled = false;
            first_ = seq;
            last_ = static_cast<uint16_t>(seq - 1);
            waitingSinceNs_ = 0;
            gapRequested_ = false;
        }
    }

    Slot& slot = slots_[seq % kLength];
    if (slot.filled && slot.packet.seq == seq) {
        return false; // Duplicate (e.g. a retransmission of a packet that arrived after all).
    }
    slot.filled = true;
    slot.packet.seq = seq;
    slot.packet.rtpTime = rtpTime;
    if (length > 0) {
        slot.packet.payload.assign(payload, payload + length);
    } else {
        slot.packet.payload.clear(); // Filler packet: keeps the sequence contiguous, plays nothing
    }
    if (seqDiff(seq, last_) > 0) {
        last_ = seq;
    }
    return true;
}

bool AudioRtpBuffer::dequeue(int64_t nowNs, int64_t maxWaitNs, Packet& out) {
    while (!empty_) {
        int count = seqDiff(last_, first_) + 1;
        if (count <= 0) return false;

        Slot& slot = slots_[first_ % kLength];
        if (slot.filled && slot.packet.seq == first_) {
            out = std::move(slot.packet);
            slot.filled = false;
            slot.packet.payload.clear();
            first_ = static_cast<uint16_t>(first_ + 1);
            waitingSinceNs_ = 0;
            gapRequested_ = false;
            return true;
        }

        // The next packet is missing while later ones are already here.
        if (waitingSinceNs_ == 0) waitingSinceNs_ = nowNs;
        bool windowFull = static_cast<size_t>(count) >= kLength;
        if (!windowFull && nowNs - waitingSinceNs_ < maxWaitNs) {
            return false; // Give a retransmission the chance to arrive.
        }
        // Give up on the whole run of missing packets at once, so a burst loss costs one wait.
        while (seqDiff(last_, first_) >= 0) {
            const Slot& next = slots_[first_ % kLength];
            if (next.filled && next.packet.seq == first_) break;
            first_ = static_cast<uint16_t>(first_ + 1);
            ++lost_;
        }
        waitingSinceNs_ = 0;
        gapRequested_ = false;
    }
    return false;
}

bool AudioRtpBuffer::takeResendRequest(uint16_t& seq, uint16_t& count) {
    if (empty_ || gapRequested_) return false;
    int total = seqDiff(last_, first_) + 1;
    if (total <= 1) return false;

    uint16_t missing = 0;
    for (int i = 0; i < total; ++i) {
        uint16_t s = static_cast<uint16_t>(first_ + i);
        const Slot& slot = slots_[s % kLength];
        if (slot.filled && slot.packet.seq == s) break;
        ++missing;
    }
    if (missing == 0 || missing >= total) return false;

    seq = first_;
    count = missing;
    gapRequested_ = true;
    return true;
}

} // namespace airplay

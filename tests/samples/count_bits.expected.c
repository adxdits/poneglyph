#include <stdint.h>

uint32_t count_bits(uint32_t value)
{
    uint32_t count = 0;
    while (value != 0) {
        count += value & 1u;
        value >>= 1;
    }
    return count;
}

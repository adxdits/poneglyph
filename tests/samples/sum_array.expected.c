#include <stdint.h>

int64_t sum_array(const int32_t *values, int count)
{
    int64_t sum = 0;
    for (int i = 0; i < count; i++) {
        sum += values[i];
    }
    return sum;
}

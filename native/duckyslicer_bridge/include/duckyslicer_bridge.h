#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

const char* duckyslicer_core_version(void);

typedef struct duckyslicer_vulkan_capabilities
{
    uint32_t loader_api_version;
    uint32_t physical_device_count;
    uint32_t device_api_version;
    uint32_t driver_version;
    uint32_t vendor_id;
    uint32_t device_id;
    uint32_t device_type;
    uint32_t compute_queue_family;
    uint8_t api_available;
    uint8_t compute_queue_available;
    uint8_t shader_int64;
    uint8_t software_device;
    uint8_t driver_probe_passed;
    uint8_t auto_candidate;
    char device_name[256];
    char reason[128];
} duckyslicer_vulkan_capabilities;

void duckyslicer_probe_vulkan(duckyslicer_vulkan_capabilities* capabilities);

#ifdef __cplusplus
}
#endif

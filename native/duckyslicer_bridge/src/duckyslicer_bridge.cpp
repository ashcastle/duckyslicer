#include "duckyslicer_bridge.h"

#include <algorithm>
#include <cctype>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#if defined(__ANDROID__)
#include <vulkan/vulkan.h>
#endif

const char* duckyslicer_core_version(void) { return "DuckySlicer native bridge (Android ARM64 runtime)"; }

namespace {

void set_text(char* destination, size_t capacity, const char* value)
{
    if (capacity == 0)
        return;
    std::snprintf(destination, capacity, "%s", value == nullptr ? "" : value);
}

#if defined(__ANDROID__)
bool is_software_device(const VkPhysicalDeviceProperties& properties)
{
    std::string name(properties.deviceName);
    std::transform(name.begin(), name.end(), name.begin(), [](unsigned char value) { return static_cast<char>(std::tolower(value)); });
    return properties.deviceType == VK_PHYSICAL_DEVICE_TYPE_CPU || name.find("swiftshader") != std::string::npos ||
           name.find("llvmpipe") != std::string::npos;
}

int device_score(const VkPhysicalDeviceProperties& properties, bool has_compute_queue)
{
    if (!has_compute_queue)
        return 0;
    int score = is_software_device(properties) ? 1 : 100;
    if (properties.deviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU)
        score += 30;
    if (properties.deviceType == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU)
        score += 20;
    return score;
}
#endif

} // namespace

void duckyslicer_probe_vulkan(duckyslicer_vulkan_capabilities* capabilities)
{
    if (capabilities == nullptr)
        return;
    std::memset(capabilities, 0, sizeof(*capabilities));
    capabilities->compute_queue_family = UINT32_MAX;

#if !defined(__ANDROID__)
    set_text(capabilities->reason, sizeof(capabilities->reason), "not_android");
#else
    capabilities->api_available      = 1;
    capabilities->loader_api_version = VK_API_VERSION_1_0;
    auto enumerate_instance_version  = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(
        vkGetInstanceProcAddr(VK_NULL_HANDLE, "vkEnumerateInstanceVersion"));
    if (enumerate_instance_version != nullptr) {
        enumerate_instance_version(&capabilities->loader_api_version);
    }

    VkApplicationInfo application_info{};
    application_info.sType              = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    application_info.pApplicationName   = "DuckySlicer";
    application_info.applicationVersion = 1;
    application_info.pEngineName        = "DuckySlicer Vulkan probe";
    application_info.engineVersion      = 1;
    application_info.apiVersion         = VK_API_VERSION_1_0;

    VkInstanceCreateInfo instance_info{};
    instance_info.sType            = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instance_info.pApplicationInfo = &application_info;
    VkInstance instance            = VK_NULL_HANDLE;
    if (vkCreateInstance(&instance_info, nullptr, &instance) != VK_SUCCESS) {
        set_text(capabilities->reason, sizeof(capabilities->reason), "instance_creation_failed");
        return;
    }

    uint32_t device_count               = 0;
    VkResult enumerate_result           = vkEnumeratePhysicalDevices(instance, &device_count, nullptr);
    capabilities->physical_device_count = device_count;
    if (enumerate_result != VK_SUCCESS || device_count == 0) {
        set_text(capabilities->reason, sizeof(capabilities->reason), "no_physical_device");
        vkDestroyInstance(instance, nullptr);
        return;
    }

    std::vector<VkPhysicalDevice> devices(device_count);
    enumerate_result = vkEnumeratePhysicalDevices(instance, &device_count, devices.data());
    if (enumerate_result != VK_SUCCESS) {
        set_text(capabilities->reason, sizeof(capabilities->reason), "device_enumeration_failed");
        vkDestroyInstance(instance, nullptr);
        return;
    }

    VkPhysicalDevice selected = VK_NULL_HANDLE;
    VkPhysicalDeviceProperties selected_properties{};
    uint32_t selected_queue_family = UINT32_MAX;
    int selected_score             = -1;
    for (VkPhysicalDevice device : devices) {
        VkPhysicalDeviceProperties properties{};
        vkGetPhysicalDeviceProperties(device, &properties);
        uint32_t queue_count = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(device, &queue_count, nullptr);
        std::vector<VkQueueFamilyProperties> queues(queue_count);
        vkGetPhysicalDeviceQueueFamilyProperties(device, &queue_count, queues.data());
        uint32_t compute_queue_family = UINT32_MAX;
        for (uint32_t index = 0; index < queue_count; ++index) {
            if ((queues[index].queueFlags & VK_QUEUE_COMPUTE_BIT) != 0 && queues[index].queueCount > 0) {
                compute_queue_family = index;
                break;
            }
        }
        const int score = device_score(properties, compute_queue_family != UINT32_MAX);
        if (score > selected_score) {
            selected              = device;
            selected_properties   = properties;
            selected_queue_family = compute_queue_family;
            selected_score        = score;
        }
    }

    capabilities->device_api_version      = selected_properties.apiVersion;
    capabilities->driver_version          = selected_properties.driverVersion;
    capabilities->vendor_id               = selected_properties.vendorID;
    capabilities->device_id               = selected_properties.deviceID;
    capabilities->device_type             = static_cast<uint32_t>(selected_properties.deviceType);
    capabilities->compute_queue_family    = selected_queue_family;
    capabilities->compute_queue_available = selected_queue_family != UINT32_MAX;
    capabilities->software_device         = is_software_device(selected_properties);
    set_text(capabilities->device_name, sizeof(capabilities->device_name), selected_properties.deviceName);

    VkPhysicalDeviceFeatures features{};
    vkGetPhysicalDeviceFeatures(selected, &features);
    capabilities->shader_int64 = features.shaderInt64 == VK_TRUE;

    if (!capabilities->compute_queue_available) {
        set_text(capabilities->reason, sizeof(capabilities->reason), "compute_queue_unavailable");
        vkDestroyInstance(instance, nullptr);
        return;
    }

    const float queue_priority = 1.0f;
    VkDeviceQueueCreateInfo queue_info{};
    queue_info.sType            = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queue_info.queueFamilyIndex = selected_queue_family;
    queue_info.queueCount       = 1;
    queue_info.pQueuePriorities = &queue_priority;
    VkDeviceCreateInfo device_info{};
    device_info.sType                = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    device_info.queueCreateInfoCount = 1;
    device_info.pQueueCreateInfos    = &queue_info;
    VkDevice logical_device          = VK_NULL_HANDLE;
    if (vkCreateDevice(selected, &device_info, nullptr, &logical_device) != VK_SUCCESS) {
        set_text(capabilities->reason, sizeof(capabilities->reason), "logical_device_creation_failed");
        vkDestroyInstance(instance, nullptr);
        return;
    }
    capabilities->driver_probe_passed = 1;
    vkDestroyDevice(logical_device, nullptr);
    vkDestroyInstance(instance, nullptr);

    if (capabilities->software_device) {
        set_text(capabilities->reason, sizeof(capabilities->reason), "software_vulkan_device");
        return;
    }
    capabilities->auto_candidate = 1;
    set_text(capabilities->reason, sizeof(capabilities->reason), "benchmark_required");
#endif
}

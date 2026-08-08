#pragma once

#include <boost/uuid/detail/md5.hpp>
#include <cstddef>

#define MD5_DIGEST_LENGTH 16

struct MD5_CTX {
    boost::uuids::detail::md5 implementation;
};

inline int MD5_Init(MD5_CTX* context)
{
    if (context == nullptr) return 0;
    context->implementation = boost::uuids::detail::md5{};
    return 1;
}

inline int MD5_Update(MD5_CTX* context, const void* data, std::size_t size)
{
    if (context == nullptr || (data == nullptr && size != 0)) return 0;
    context->implementation.process_bytes(data, size);
    return 1;
}

inline int MD5_Final(unsigned char* digest, MD5_CTX* context)
{
    if (context == nullptr || digest == nullptr) return 0;
    boost::uuids::detail::md5::digest_type words{};
    context->implementation.get_digest(words);
    const auto* bytes = reinterpret_cast<const unsigned char*>(words);
    for (std::size_t index = 0; index < MD5_DIGEST_LENGTH; ++index) {
        digest[index] = bytes[index];
    }
    return 1;
}


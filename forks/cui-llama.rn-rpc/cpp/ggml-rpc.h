#pragma once

#include "ggml-backend.h"

#ifdef  __cplusplus
extern "C" {
#endif

#define RPC_PROTO_MAJOR_VERSION    4
#define RPC_PROTO_MINOR_VERSION    0
#define RPC_PROTO_PATCH_VERSION    0

#ifdef  __cplusplus
static_assert(LM_GGML_OP_COUNT == 96, "LM_GGML_OP_COUNT has changed - update RPC_PROTO_PATCH_VERSION");
#endif

#define LM_GGML_RPC_MAX_SERVERS       16

// backend API
LM_GGML_BACKEND_API lm_ggml_backend_t lm_ggml_backend_rpc_init(const char * endpoint, uint32_t device);
LM_GGML_BACKEND_API bool lm_ggml_backend_is_rpc(lm_ggml_backend_t backend);

LM_GGML_BACKEND_API lm_ggml_backend_buffer_type_t lm_ggml_backend_rpc_buffer_type(const char * endpoint, uint32_t device);

LM_GGML_BACKEND_API void lm_ggml_backend_rpc_get_device_memory(const char * endpoint, uint32_t device, size_t * free, size_t * total);

LM_GGML_BACKEND_API void lm_ggml_backend_rpc_start_server(const char * endpoint, const char * cache_dir,
                                                    size_t n_threads, size_t n_devices, lm_ggml_backend_dev_t * devices);

LM_GGML_BACKEND_API lm_ggml_backend_reg_t lm_ggml_backend_rpc_reg(void);
LM_GGML_BACKEND_API lm_ggml_backend_reg_t lm_ggml_backend_rpc_add_server(const char * endpoint);

#ifdef  __cplusplus
}
#endif

#ifndef PAGER_PGP_BRIDGE_H
#define PAGER_PGP_BRIDGE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    uint8_t *data;
    size_t len;
} PgpBuffer;

char *pager_pgp_generate(const char *user_id);
char *pager_pgp_encrypt(const uint8_t *data, size_t len, const char *public_armor);
PgpBuffer pager_pgp_decrypt(const char *armor, const char *secret_armor);
void pager_pgp_free_string(char *value);
void pager_pgp_free_buffer(PgpBuffer buffer);
char *pager_pgp_last_error(void);

#ifdef __cplusplus
}
#endif

#endif

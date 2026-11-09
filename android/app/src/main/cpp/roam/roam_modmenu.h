#ifndef NFSU2_ROAM_MODMENU_H
#define NFSU2_ROAM_MODMENU_H

/* Offline-only Mod Menu switches. Values are supplied by the Android UI and
 * are read on the game/render thread. */
#define NFSU2_MOD_AUTO_SHIFT   (1u << 0)
#define NFSU2_MOD_INFINITE_NOS (1u << 1)

int  roam_modmenu_flags(void);
int  roam_modmenu_shift_key(void);
void roam_modmenu_key_pulse(int scan);

#endif

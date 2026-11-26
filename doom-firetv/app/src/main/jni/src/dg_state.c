/* Acesso ao estado interno do Doom (sem SDL, para não misturar headers). */
#include "doomstat.h"
#include "d_player.h"
#include "doomdef.h"

int dg_menu_active(void)
{
    return menuactive ? 1 : 0;
}

static int key_for_weapon(int w)
{
    switch (w)
    {
        case wp_fist:         return '1';
        case wp_chainsaw:     return '1';
        case wp_pistol:       return '2';
        case wp_shotgun:      return '3';
        case wp_supershotgun: return '3';
        case wp_chaingun:     return '4';
        case wp_missile:      return '5';
        case wp_plasma:       return '6';
        case wp_bfg:          return '7';
    }
    return 0;
}

/* Tecla numérica da próxima (dir=+1) ou anterior (dir=-1) arma que o jogador possui. */
int dg_weapon_key(int dir)
{
    static const int order[9] = { wp_fist, wp_chainsaw, wp_pistol, wp_shotgun,
                                  wp_supershotgun, wp_chaingun, wp_missile,
                                  wp_plasma, wp_bfg };
    player_t *p;
    int cur = 0, i, n;

    if (gamestate != GS_LEVEL)
        return 0;

    p = &players[consoleplayer];
    for (i = 0; i < 9; i++)
        if (order[i] == (int) p->readyweapon)
            cur = i;

    for (n = 1; n <= 9; n++)
    {
        int idx = (((cur + dir * n) % 9) + 9) % 9;
        int w = order[idx];
        if (p->weaponowned[w])
            return key_for_weapon(w);
    }
    return 0;
}

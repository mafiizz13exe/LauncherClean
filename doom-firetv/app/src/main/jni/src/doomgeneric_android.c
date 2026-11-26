/*
 * doomgeneric -> Android / Fire TV (SDL2)
 *
 * Entradas suportadas:
 *  - Controle remoto do Fire TV: setas = andar/virar, OK(Select) = atirar,
 *    Play/Pause = usar (abrir portas), Rewind/FastForward = deslizar lateral,
 *    Menu = próxima arma, Voltar = menu do jogo (ESC).
 *  - Gamepad (Xbox/Luna/8BitDo...): analógico esq. = andar/deslizar,
 *    analógico dir. = virar, RT/A = atirar, B/X = usar, LT = correr,
 *    LB/RB = trocar arma, Start = menu, Back/Select = mapa.
 *  - Teclado USB/Bluetooth.
 */
#include "doomkeys.h"
#include "m_argv.h"
#include "doomgeneric.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <ctype.h>
#include <stdbool.h>
#include <SDL.h>

int dg_menu_active(void);
int dg_weapon_key(int dir);

static SDL_Window   *window   = NULL;
static SDL_Renderer *renderer = NULL;
static SDL_Texture  *texture  = NULL;

/* ---------- fila de teclas ---------- */
#define KEYQUEUE_SIZE 256
static unsigned short s_KeyQueue[KEYQUEUE_SIZE];
static unsigned int s_w = 0, s_r = 0;

static void push(int pressed, unsigned char key)
{
    if (!key) return;
    s_KeyQueue[s_w] = (unsigned short)((pressed << 8) | key);
    s_w = (s_w + 1) % KEYQUEUE_SIZE;
}

/* toques curtos (a tecla precisa ficar apertada pelo menos 1 tic do Doom) */
typedef struct { unsigned char key; Uint32 at; } pending_t;
static pending_t pend[16];

static void tap(unsigned char key)
{
    int i;
    if (!key) return;
    for (i = 0; i < 16; i++)
    {
        if (!pend[i].key)
        {
            push(1, key);
            pend[i].key = key;
            pend[i].at = SDL_GetTicks() + 120;
            return;
        }
    }
}

static void flush_pending(void)
{
    int i;
    Uint32 now = SDL_GetTicks();
    for (i = 0; i < 16; i++)
    {
        if (pend[i].key && (int)(now - pend[i].at) >= 0)
        {
            push(0, pend[i].key);
            pend[i].key = 0;
        }
    }
}

/* tecla que muda conforme o menu estar aberto ou não; solta a mesma que apertou */
#define NLATCH 32
static unsigned char latched[NLATCH];

static void press_ctx(int id, int pressed, unsigned char menukey, unsigned char gamekey)
{
    if (pressed)
    {
        unsigned char k = dg_menu_active() ? menukey : gamekey;
        if (latched[id]) push(0, latched[id]);
        latched[id] = k;
        push(1, k);
    }
    else if (latched[id])
    {
        push(0, latched[id]);
        latched[id] = 0;
    }
}

enum {
    L_SELECT, L_PLAYPAUSE, L_A, L_B, L_X, L_START, L_BACK,
    L_REW, L_FF, L_MENU, L_ESC, L_LT, L_RT
};

/* ---------- teclado / controle remoto ---------- */
static unsigned char convertToDoomKey(SDL_Keycode key, int pressed, int *handled)
{
    *handled = 1;
    switch (key)
    {
    case SDLK_RETURN:     return KEY_ENTER;
    case SDLK_ESCAPE:     return KEY_ESCAPE;
    case SDLK_AC_BACK:    return KEY_ESCAPE;
    case SDLK_LEFT:       return KEY_LEFTARROW;
    case SDLK_RIGHT:      return KEY_RIGHTARROW;
    case SDLK_UP:         return KEY_UPARROW;
    case SDLK_DOWN:       return KEY_DOWNARROW;
    case SDLK_LCTRL:
    case SDLK_RCTRL:      return KEY_FIRE;
    case SDLK_SPACE:      return KEY_USE;
    case SDLK_LSHIFT:
    case SDLK_RSHIFT:     return KEY_RSHIFT;
    case SDLK_LALT:
    case SDLK_RALT:       return KEY_LALT;
    case SDLK_TAB:        return KEY_TAB;
    case SDLK_F1:  return KEY_F1;
    case SDLK_F2:  return KEY_F2;
    case SDLK_F3:  return KEY_F3;
    case SDLK_F4:  return KEY_F4;
    case SDLK_F5:  return KEY_F5;
    case SDLK_F6:  return KEY_F6;
    case SDLK_F7:  return KEY_F7;
    case SDLK_F8:  return KEY_F8;
    case SDLK_F9:  return KEY_F9;
    case SDLK_F10: return KEY_F10;
    case SDLK_F11: return KEY_F11;
    case SDLK_BACKSPACE:  return KEY_BACKSPACE;
    case SDLK_EQUALS:
    case SDLK_PLUS:       return KEY_EQUALS;
    case SDLK_MINUS:      return KEY_MINUS;
    default:
        break;
    }
    *handled = 0;
    if (key > 0 && key < 128) { *handled = 1; return (unsigned char) tolower((int) key); }
    return 0;
}

static void remote_key(SDL_Keycode key, int pressed)
{
    switch (key)
    {
    /* OK do controle remoto: atira (no menu = Enter) */
    case SDLK_SELECT:           press_ctx(L_SELECT, pressed, KEY_ENTER, KEY_FIRE); return;
    /* Play/Pause: usar/abrir (no menu = confirmar com 'y') */
    case SDLK_AUDIOPLAY:
    case SDLK_AUDIOSTOP:        press_ctx(L_PLAYPAUSE, pressed, 'y', KEY_USE); return;
    case SDLK_AUDIOREWIND:      press_ctx(L_REW, pressed, KEY_LEFTARROW, ','); return;
    case SDLK_AUDIOFASTFORWARD: press_ctx(L_FF, pressed, KEY_RIGHTARROW, '.'); return;
    case SDLK_MENU:
    case SDLK_APPLICATION:      if (pressed) { if (dg_menu_active()) tap('n'); else tap((unsigned char) dg_weapon_key(1)); } return;
    default: break;
    }
}

/* ---------- gamepad ---------- */
#define DEADZONE 9000
static int st_up, st_down, st_sl, st_sr, st_tl, st_tr, st_run, st_fire;

static void axis_key(int *state, int on, unsigned char key)
{
    if (on && !*state) { *state = 1; push(1, key); }
    else if (!on && *state) { *state = 0; push(0, key); }
}

static void controller_axis(SDL_GameControllerAxis ax, int v)
{
    switch (ax)
    {
    case SDL_CONTROLLER_AXIS_LEFTY:
    {
        static int fwd, back;
        axis_key(&fwd,  v < -DEADZONE, KEY_UPARROW);
        axis_key(&back, v >  DEADZONE, KEY_DOWNARROW);
        break;
    }
    case SDL_CONTROLLER_AXIS_LEFTX:
        axis_key(&st_sl, v < -DEADZONE, ',');
        axis_key(&st_sr, v >  DEADZONE, '.');
        break;
    case SDL_CONTROLLER_AXIS_RIGHTX:
        axis_key(&st_tl, v < -DEADZONE, KEY_LEFTARROW);
        axis_key(&st_tr, v >  DEADZONE, KEY_RIGHTARROW);
        break;
    case SDL_CONTROLLER_AXIS_TRIGGERLEFT:
        axis_key(&st_run, v > 12000, KEY_RSHIFT);
        break;
    case SDL_CONTROLLER_AXIS_TRIGGERRIGHT:
        if (v > 12000 && !st_fire) { st_fire = 1; press_ctx(L_RT, 1, KEY_ENTER, KEY_FIRE); }
        else if (v <= 12000 && st_fire) { st_fire = 0; press_ctx(L_RT, 0, 0, 0); }
        break;
    default: break;
    }
}

static void controller_button(SDL_GameControllerButton b, int pressed)
{
    switch (b)
    {
    case SDL_CONTROLLER_BUTTON_DPAD_UP:    push(pressed, KEY_UPARROW);    break;
    case SDL_CONTROLLER_BUTTON_DPAD_DOWN:  push(pressed, KEY_DOWNARROW);  break;
    case SDL_CONTROLLER_BUTTON_DPAD_LEFT:  push(pressed, KEY_LEFTARROW);  break;
    case SDL_CONTROLLER_BUTTON_DPAD_RIGHT: push(pressed, KEY_RIGHTARROW); break;
    case SDL_CONTROLLER_BUTTON_A: press_ctx(L_A, pressed, KEY_ENTER, KEY_FIRE); break;
    case SDL_CONTROLLER_BUTTON_B: press_ctx(L_B, pressed, KEY_ESCAPE, KEY_USE); break;
    case SDL_CONTROLLER_BUTTON_X: press_ctx(L_X, pressed, 'y', KEY_USE); break;
    case SDL_CONTROLLER_BUTTON_Y:
    case SDL_CONTROLLER_BUTTON_RIGHTSHOULDER:
        if (pressed && !dg_menu_active()) tap((unsigned char) dg_weapon_key(1));
        break;
    case SDL_CONTROLLER_BUTTON_LEFTSHOULDER:
        if (pressed && !dg_menu_active()) tap((unsigned char) dg_weapon_key(-1));
        break;
    case SDL_CONTROLLER_BUTTON_START: press_ctx(L_START, pressed, KEY_ESCAPE, KEY_ESCAPE); break;
    case SDL_CONTROLLER_BUTTON_BACK:  press_ctx(L_BACK, pressed, KEY_TAB, KEY_TAB); break;
    default: break;
    }
}

static void open_controllers(void)
{
    int i, n = SDL_NumJoysticks();
    for (i = 0; i < n; i++)
        if (SDL_IsGameController(i))
            SDL_GameControllerOpen(i);
}

/* ---------- eventos ---------- */
static void handleInput(void)
{
    SDL_Event e;
    while (SDL_PollEvent(&e))
    {
        switch (e.type)
        {
        case SDL_QUIT:
            exit(0);
            break;
        case SDL_KEYDOWN:
        case SDL_KEYUP:
        {
            int pressed = (e.type == SDL_KEYDOWN);
            int handled;
            unsigned char k;
            SDL_Keycode sym = e.key.keysym.sym;
            if (e.key.repeat) break;
            if (sym == SDLK_SELECT || sym == SDLK_AUDIOPLAY ||
                sym == SDLK_AUDIOSTOP || sym == SDLK_AUDIOREWIND || sym == SDLK_AUDIOFASTFORWARD ||
                sym == SDLK_MENU || sym == SDLK_APPLICATION)
            {
                remote_key(sym, pressed);
                break;
            }
            k = convertToDoomKey(sym, pressed, &handled);
            if (handled) push(pressed, k);
            break;
        }
        case SDL_CONTROLLERDEVICEADDED:
            SDL_GameControllerOpen(e.cdevice.which);
            break;
        case SDL_CONTROLLERBUTTONDOWN:
            controller_button((SDL_GameControllerButton) e.cbutton.button, 1);
            break;
        case SDL_CONTROLLERBUTTONUP:
            controller_button((SDL_GameControllerButton) e.cbutton.button, 0);
            break;
        case SDL_CONTROLLERAXISMOTION:
            controller_axis((SDL_GameControllerAxis) e.caxis.axis, e.caxis.value);
            break;
        default:
            break;
        }
    }
    flush_pending();
}

/* ---------- API do doomgeneric ---------- */
void DG_Init(void)
{
    SDL_SetHint(SDL_HINT_ANDROID_TRAP_BACK_BUTTON, "1");
    SDL_SetHint(SDL_HINT_RENDER_SCALE_QUALITY, "1");

    SDL_Init(SDL_INIT_VIDEO | SDL_INIT_GAMECONTROLLER | SDL_INIT_JOYSTICK);

    window = SDL_CreateWindow("DOOM", SDL_WINDOWPOS_UNDEFINED, SDL_WINDOWPOS_UNDEFINED,
                              0, 0, SDL_WINDOW_FULLSCREEN | SDL_WINDOW_SHOWN);
    renderer = SDL_CreateRenderer(window, -1, SDL_RENDERER_ACCELERATED);
    if (!renderer)
        renderer = SDL_CreateRenderer(window, -1, SDL_RENDERER_SOFTWARE);

    /* O Doom é 4:3 com pixels não quadrados: 640x400 esticado para 640x480 */
    SDL_RenderSetLogicalSize(renderer, 640, 480);
    SDL_SetRenderDrawColor(renderer, 0, 0, 0, 255);
    SDL_RenderClear(renderer);
    SDL_RenderPresent(renderer);

    texture = SDL_CreateTexture(renderer, SDL_PIXELFORMAT_RGB888, SDL_TEXTUREACCESS_STREAMING,
                                DOOMGENERIC_RESX, DOOMGENERIC_RESY);
    open_controllers();
}

void DG_DrawFrame(void)
{
    SDL_UpdateTexture(texture, NULL, DG_ScreenBuffer, DOOMGENERIC_RESX * sizeof(uint32_t));
    SDL_RenderClear(renderer);
    SDL_RenderCopy(renderer, texture, NULL, NULL);
    SDL_RenderPresent(renderer);
    handleInput();
}

void DG_SleepMs(uint32_t ms)
{
    SDL_Delay(ms);
}

uint32_t DG_GetTicksMs(void)
{
    return SDL_GetTicks();
}

int DG_GetKey(int *pressed, unsigned char *doomKey)
{
    unsigned short d;
    if (s_r == s_w) return 0;
    d = s_KeyQueue[s_r];
    s_r = (s_r + 1) % KEYQUEUE_SIZE;
    *pressed = d >> 8;
    *doomKey = d & 0xFF;
    return 1;
}

void DG_SetWindowTitle(const char *title)
{
    if (window) SDL_SetWindowTitle(window, title);
}

int main(int argc, char **argv)
{
    int i;
    /* -workdir <pasta>: onde o Doom grava savegames e configuração */
    for (i = 1; i + 1 < argc; i++)
        if (!strcmp(argv[i], "-workdir"))
            chdir(argv[i + 1]);

    doomgeneric_Create(argc, argv);
    for (;;)
        doomgeneric_Tick();
    return 0;
}

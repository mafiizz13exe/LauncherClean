# APK Installer — dados para o Amazon Appstore

| Campo | Valor |
|---|---|
| **Título** | `APK Installer – XAPK & APKS` (27 caracteres) |
| **SKU** | `APKINSTALLER-FIRETV-001` |
| **Package name** | `com.mafiizz.apkinstaller` |
| **Versão** | 2.0 (versionCode 3) |
| **Categoria sugerida** | Utilities (Utilitários) |
| **Dispositivos** | Fire TV (controle remoto, sem touchscreen); também roda em tablets Fire |

> O SKU é só um identificador interno seu no console da Amazon — escolha uma vez e não mude depois.

## Descrição curta (PT-BR)
Instale APK, XAPK, APKS e APKM direto na sua Fire TV, com ou sem link.

## Descrição longa (PT-BR)
O APK Installer é o jeito mais simples de instalar aplicativos em arquivo na sua TV, tudo navegável com o controle remoto.

• Instala APK, XAPK, APKS e APKM (pacotes divididos), inclusive com arquivos de dados OBB
• Encontra automaticamente os arquivos na pasta Downloads, no armazenamento interno e em pendrives
• Baixa por link: cole o endereço do arquivo, acompanhe o progresso e instale em seguida
• Mostra ícone, nome e versão de cada arquivo e avisa se o app já está instalado ou se é uma atualização
• Escolhe sozinho só as partes do pacote que servem para o seu aparelho (arquitetura, tela e idioma)
• Apaga arquivos antigos com um clique para liberar espaço
• Interface escura, com foco bem visível e feita para o controle remoto

Importante: instale apenas aplicativos de fontes em que você confia. O Android pedirá a permissão "Permitir desta fonte" na primeira instalação.

## Short description (EN)
Install APK, XAPK, APKS and APKM files on your Fire TV — from storage or a link.

## Long description (EN)
APK Installer is the simplest way to install app files on your TV, fully navigable with the remote.

• Installs APK, XAPK, APKS and APKM (split packages), including OBB data files
• Automatically finds files in Downloads, internal storage and USB drives
• Download by link: paste the file address, follow the progress and install right after
• Shows icon, name and version of every file and tells you if the app is already installed or is an update
• Picks only the package parts that fit your device (architecture, screen density and language)
• Deletes old files in one click to free up space
• Dark interface with clear focus, built for the remote control

Important: only install apps from sources you trust. Android will ask for the "Allow from this source" permission on the first install.

## Palavras-chave
apk, xapk, apks, apkm, installer, instalador, sideload, fire tv, utilitário, arquivos

## Notas de versão 2.0
Nova interface: ícones dos apps, selos de tipo/estado (Instalado, Atualização), foco animado, diálogos escuros, novo ícone e banner.

## Imagens (pasta `store/`)
- `icon-512x512.png` — ícone grande
- `icon-114x114.png` — ícone pequeno
- `firetv-banner-1280x720.png` — banner/ícone Fire TV
- `firetv-background-1920x1080.png` — fundo (sem texto)
- Screenshots: tire 3–5 capturas **1920×1080** direto da TV (ADB: `adb shell screencap -p /sdcard/s.png`)

Confira no console da Amazon se as dimensões exigidas continuam essas na hora de enviar.

## Pontos de atenção na revisão da Amazon
- O app usa `REQUEST_INSTALL_PACKAGES` e `WRITE_EXTERNAL_STORAGE`. Apps instaladores costumam passar por revisão manual: explique no campo de notas ao revisor que o app só instala arquivos que o próprio usuário escolhe ou baixa por link digitado por ele.
- A política de conteúdo da Amazon proíbe distribuir/estimular pirataria. Evite exemplos de links ou prints com apps pagos crackeados.
- `targetSdk 28` é intencional (listar Downloads sem "acesso a todos os arquivos" no Fire OS). Se a Amazon exigir um target maior, será preciso migrar para SAF/MANAGE_EXTERNAL_STORAGE.

# Conexão pelo Mac

## Código na televisão

Ative **Configurações → Segurança do AirPlay → Solicitar código (experimental)** no Buzin Cast. Essa alteração reinicia o receptor. Conecte novamente pelo Mac e informe o código mostrado na TV quando o macOS solicitar. O código não protege DLNA, FCast ou Cast V2.

O código é gerado ao iniciar o receptor. O pareamento e as credenciais lembradas variam conforme o emissor; a compatibilidade com o Mac do usuário ainda precisa de validação. Não é uma conta, senha de cadastro ou login pela internet.

## Escolher antes de compartilhar

A [documentação Apple sobre AirPlay](https://support.apple.com/en-ie/guide/mac-help/mchld7e543a0/mac) descreve uma escolha no Mac entre compartilhar uma janela, espelhar ou estender. Salvar um padrão pode fazer a conexão seguinte iniciar diretamente. A versão do macOS e a compatibilidade da conexão influenciam as opções exibidas.

Para alterar a escolha, use **Central de Controle → Espelhamento de Tela → Alterar**, quando disponível. Outra rota é **Ajustes do Sistema → Telas → Buzin Cast → Usar como**. Consulte [as opções de telas da Apple](https://support.apple.com/guide/mac-help/mh40768/mac). A localização exata varia entre versões.

O usuário informou macOS 27.2. Em **Ajustes do Sistema → Telas**, procure **Quando conectado à TV** (*When connected to TV*) e selecione a opção para perguntar o que mostrar (*Ask What to Show*), quando disponível. A [Apple informa que esse controle aparece em Macs com Apple silicon](https://support.apple.com/en-nz/guide/mac-help/-mh40768/mac). Depois, desconecte e conecte novamente ao Buzin para conferir se o seletor é apresentado. O usuário localizou o ajuste e confirmou a apresentação do menu nesse Mac.

- **Espelhar:** o Mac envia uma cópia do conteúdo selecionado.
- **Estender:** o Mac cria uma área de trabalho adicional e envia a imagem dela ao receptor.

O Buzin recebe o vídeo enviado. Ele não possui uma API para forçar a janela do macOS a aparecer, mudar a preferência salva no computador ou criar sozinho um monitor virtual no Mac. A apresentação do seletor foi confirmada pelo usuário. Uma sessão estendida completa ainda precisa ser verificada antes de anunciar essa compatibilidade como validada.

## Encaixe automático

**Configurações → Imagem → Encaixe da imagem → Automático** usa a área informada pelo Fire OS, sem a margem extra. Mantém a proporção e a imagem inteira, podendo deixar barras quando a proporção da fonte é diferente da televisão.

Isso não detecta o recorte físico do painel. Com corte externo confirmado, verifique o formato de imagem da televisão. [Samsung: tamanho da imagem](https://www.samsung.com/ae/support/tv-audio-video/adjust-the-picture-size-on-your-samsung-tv/) e [LG: imagem ampliada ou cortada](https://www.lg.com/us/support/help-library/how-to-fix-a-zoomedin-picture-or-cutoff-text-on-your-lg-tv-CT10000018-20155399003922) têm orientações; o caminho correto depende do modelo.

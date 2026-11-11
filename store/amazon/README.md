# Materiais da Amazon Appstore

Preço autorizado: **gratuito**, sem anúncios, assinatura ou compras no app.
Distribuição: países e regiões elegíveis do console Amazon, somente Fire TV
com Fire OS. Nenhum VPKG Vega integra esta submissão.

- `listing.*.json`: textos da página, recursos, palavras-chave e instruções
  de teste para a revisão nos 13 idiomas da ficha.
- `images/icon-512.png`, `images/icon-114.png` e
  `images/fire-tv-icon-1280x720.png` usam a mesma composição com o símbolo
  verde, o nome centralizado e órbitas sobre fundo azul escuro.
- `images/fire-tv-background-1920x1080.png`: fundo geométrico com a paleta e
  as órbitas do app, sem títulos ou logos, conforme a orientação da Amazon.
- `images/screenshots/pt-BR`: capturas nativas 1920×1080 do APK principal
  0.6.3/código 28 de produção, no Fire TV Stick AFTMM/API 25 do quarto,
  em 6 de novembro de 2026. Reencodificação RGB24 sem recorte, montagem,
  mudança de interface ou conteúdo pessoal. A interface está em português.
- [Política de privacidade em português e inglês](../../docs/PRIVACY.md).

Para regenerar os formatos gráficos a partir das capturas originais locais:

```powershell
powershell.exe -NoProfile -File ./tools/PrepareAmazonAssets.ps1 -CaptureDirectory '<pasta com as três capturas>'
```

Use “Buzin Cast” como título da ficha e descreva o espelhamento como
funcionalidade. iPhone, iPad e Mac aparecem como referências de
compatibilidade. Os textos comuns às edições descrevem apenas os recursos
disponíveis em ambas, incluindo recepção experimental de mídia e
retransmissão ao vivo. A edição restrita desabilita download de mídia e
gravação local. Não anunciar catálogo de filmes/canais, acesso a conteúdo
protegido, Google Cast certificado, suporte Vega, desempenho/4K garantidos
ou teste físico em todos os modelos selecionados.

Fontes de requisitos: [detalhes da loja](https://developer.amazon.com/docs/app-submission/appstore-details.html)
e [privacidade](https://developer.amazon.com/docs/app-submission/appstore-privacy-labels.html).

# Materiais da Amazon Appstore

Preço autorizado: **gratuito**, sem anúncios, assinatura ou compras no app.
Distribuição: países e regiões elegíveis do console Amazon, somente Fire TV
Stick físicos com Fire OS. Nenhum VPKG Vega integra esta submissão.

- `listing.*.json`: textos da página, recursos, palavras-chave e instruções
  de teste para a revisão nas 15 localizações da ficha (incluindo inglês
  britânico e espanhol do México, além das 13 variantes de base).
- `review-instructions.en.txt`: instruções gerais de teste enviadas no
  rascunho 0.6.6, incluindo a separação regional e a integração Appstore.
- `deat/listing.en-US.json` e `deat/listing.de.json`: textos efetivamente
  conferidos na ficha regional, restrita à Alemanha e à Áustria.
- `images/icon-512.png`, `images/icon-114.png` e
  `images/fire-tv-icon-1280x720.png` usam a mesma composição com o símbolo
  verde, o nome centralizado e órbitas sobre fundo azul escuro.
- `images/fire-tv-background-1920x1080.png`: fundo geométrico com a paleta e
  as órbitas do app, sem títulos ou logos, conforme a orientação da Amazon.
- `images/screenshots/pt-BR`: capturas nativas 1920×1080 do APK principal
  0.6.3/código 28 de produção, no Fire TV Stick AFTMM/API 25 do quarto,
  em 6 de novembro de 2026. Reencodificação RGB24 sem recorte, montagem,
  mudança de interface ou conteúdo pessoal. A interface está em português.
- `images/screenshots/normal/<localidade>`: capturas atuais da versão 0.6.6
  em inglês US/AU/GB/IN, alemão, espanhol ES/MX, francês, italiano, japonês
  e português brasileiro. Cada conjunto contém início, configurações e
  Sobre, sem Diagnóstico no menu. O conjunto sueco adicional valida a
  tradução dentro do app; o console não oferece uma localização sueca de
  imagens.
  As pastas `en-US` e `en-GB` compartilham o conjunto inglês salvo no
  console, incluindo exatamente os mesmos arquivos de captura.
- `images/screenshots/deat/<localidade>`: capturas da edição regional
  0.6.5-nodownload em alemão e inglês US (padrão obrigatório do console).
  Todas foram obtidas no Stick do quarto em 15/nov/2026 e salvas em PNG RGB
  1920×1080, sem composição ou tradução artificial sobre a imagem.
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

O console atribui um APK por modelo dentro de cada ficha. Para a distribuição
da edição restrita na Alemanha e na Áustria, a ficha regional usa o pacote
`com.buzincast.tv.deat` e uma chave pública Appstore própria. A ficha
principal usa `com.buzincast.tv` e exclui os dois países.

Para gerar os APKs regionais, use `-PbuzinRegionalAppstoreAssets=<diretório>`
com um diretório que contenha apenas o `AppstoreAuthenticationKey.pem` da
ficha regional. Essa opção desativa download e gravação. Use também
`-PbuzinLegacy=true` para Fire OS 5. Ao alternar entre chaves em um mesmo
checkout, execute `:app:mergeReleaseAssets --rerun :app:assembleRelease`
e confira a chave dentro de cada APK antes de subir ao console.

Fontes de requisitos: [detalhes da loja](https://developer.amazon.com/docs/app-submission/appstore-details.html)
e [privacidade](https://developer.amazon.com/docs/app-submission/appstore-privacy-labels.html).

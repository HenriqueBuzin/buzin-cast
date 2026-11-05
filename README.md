# Buzin Cast

Compartilhe a tela do iPhone, iPad ou Mac na Fire TV pela rede local. O Buzin Cast é um receptor de código aberto, gratuito, sem anúncios e sem limite de tempo por sessão.

![Buzin Cast](app/src/main/res/drawable-nodpi/buzin_banner.png)

**Criado e mantido por Henrique Buzin.** O projeto deriva do android-airplay-server, de jqssun e contribuidores, e é distribuído sob a [GNU GPL v3](LICENSE). [Autoria e créditos](AUTHORS.md) · [Licenças de terceiros](THIRD_PARTY_NOTICES.md)

## O que dá para fazer

- Espelhar a tela com áudio via AirPlay e ajustar o encaixe da imagem na TV.
- Personalizar o nome do receptor e, se quiser, aprovar conexões pelo controle.
- Escolher entre os perfis Padrão, Cinema, Apresentação e Jogos.
- Experimentar a reprodução de mídia via DLNA e FCast, a gravação de espelhamento e a retransmissão entre dois Buzin Cast. Veja o [estado e os limites de cada recurso](docs/FEATURES.md).

## Como testar

O projeto está em desenvolvimento, com instalação de teste por APK. A edição principal é para Fire OS 6 ou posterior. Há uma edição de compatibilidade para Fire OS 5 e uma [prévia para Vega OS](vega/README.md) ainda sem recepção de transmissões. A disponibilidade por modelo depende da validação em aparelho.

1. Siga as [instruções de compilação e instalação](BUZIN_CAST.md).
2. Abra o Buzin Cast e conecte a Fire TV e o dispositivo transmissor à mesma rede.
3. No dispositivo Apple, abra **Espelhamento de Tela** e escolha **Buzin Cast** ou o nome que você configurou.

Para relatar um problema, abra uma [issue no GitHub](https://github.com/HenriqueBuzin/buzin-cast/issues) com o modelo da Fire TV, a versão do sistema e os passos para reproduzir.

## Documentação

- [Histórico técnico, instalação e publicação](BUZIN_CAST.md)
- [Arquitetura](docs/ARCHITECTURE_REVIEW.md) e [testes e cobertura](docs/TESTING.md)
- [Dependências e compatibilidade](docs/DEPENDENCIES.md)
- [Andamento do trabalho](docs/WORK_CHECKPOINT.md) e [retomada do Vega OS no Mac](docs/VEGA_HANDOFF.md)
- [Identidade visual e ícone](docs/BRAND.md)
- [Documentação histórica do projeto original](docs/UPSTREAM.md)

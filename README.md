[![Javadoc](https://img.shields.io/badge/javadoc-4.1.0-brightgreen.svg)](https://scivicslab.github.io/Turing-workflow-plugins/plugin-llm/apidocs/)
[![Sponsor](https://img.shields.io/github/sponsors/scivicslab)](https://github.com/sponsors/scivicslab)

# Turing Workflow Plugins

**Official Website: [scivicslab.com](https://scivicslab.com)**

Plugin collection for the [Turing-workflow](https://github.com/scivicslab/Turing-workflow) engine. Each module registers actors a workflow step can name under `actor`, and each of their actions declares what it takes.

## Using a plugin

Add the module a workflow needs. The parent and every module carry the same version.

```xml
<dependency>
    <groupId>com.scivicslab.turingworkflow.plugins</groupId>
    <artifactId>plugin-llm</artifactId>
    <version>4.1.0</version>
</dependency>
```

A workflow names the fields the action declares:

```yaml
- states: ["0", "1"]
  actions:
    - actor: llm
      method: setOpenAiUrl
      arguments:
        url: "http://192.168.5.15:8000"
        model: "Qwen/Qwen3-32B"
- states: ["1", "end"]
  actions:
    - actor: llm
      method: callOpenAi
      arguments:
        prompt: "jexl: actors.get('str:content').get()"
```

## Modules

| Module | What its actors do |
|---|---|
| `plugin-chatui3` | Calls quarkus-chat-ui3 — chat over SSE, trace, configuration |
| `plugin-codedoc` | Scans a source tree, chunks files, writes documents |
| `plugin-fineweb-search` | Queries a FineWeb BM25 search server |
| `plugin-inventory` | Reads an Ansible inventory and manages node groups |
| `plugin-kana-kanji` | Turns OCR output into kana-kanji pairs via vLLM |
| `plugin-llm` | Calls an LLM, either over MCP or an OpenAI-compatible endpoint |
| `plugin-log-db` | Stores a workflow run's log in an H2 database |
| `plugin-log-output` | Writes log output to the console, a file, or several at once |
| `plugin-ocr` | Sends a PDF to Marker (English and mathematics) or YomiToku (Japanese) |
| `plugin-openalex` | Queries the OpenAlex scholarly metadata API |
| `plugin-prompt-builder` | Assembles a prompt from warnings, context and message sections |
| `plugin-report` | Builds a run report out of sections |
| `plugin-secret` | Encrypts and decrypts secrets with AES-256-GCM |
| `plugin-ssh` | Runs commands on remote nodes over SSH |
| `plugin-vault` | Reads secrets from HashiCorp Vault |
| `plugin-web` | Searches the web with DuckDuckGo and fetches a URL as Markdown |

## Example: `plugin-llm`

Actors that call an LLM, either over the [MCP](https://modelcontextprotocol.io/) Streamable HTTP transport or an OpenAI-compatible endpoint.

| Action | Arguments | What it does |
|---|---|---|
| `setDirectUrl` | `url` | Sets the MCP server's base URL |
| `setOpenAiUrl` | `url`, `model` | Sets the OpenAI-compatible endpoint, and which model answers |
| `setSystemPrompt` | `prompt` | Sets the system prompt sent with every call |
| `setEnableThinking` | `enabled` | Whether the model's reasoning output is kept |
| `callOpenAi` | `prompt` | Sends a prompt to the OpenAI-compatible endpoint |
| `submitDirect` | `prompt` | Sends a prompt over MCP |

Every field above is required except `model`, which falls back to the endpoint's own default. The generated JSON Schema for each action is in the module's jar under `action-schemas/`.

## Build

```bash
mvn install
```

## Dependencies

- [POJO-actor](https://github.com/scivicslab/POJO-actor) 4.1.0
- [Turing-workflow](https://github.com/scivicslab/Turing-workflow) 4.1.0

## License

[Apache License 2.0](LICENSE)

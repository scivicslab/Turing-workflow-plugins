[![Javadoc](https://img.shields.io/badge/javadoc-1.0.0-brightgreen.svg)](https://scivicslab.github.io/Turing-workflow-plugins/plugin-llm/apidocs/)

# Turing Workflow Plugins

**Official Website: [scivicslab.com](https://scivicslab.com)**

Plugin collection for the [Turing-workflow](https://github.com/scivicslab/Turing-workflow) engine.

## Modules

### plugin-llm

Actor that calls LLM services via the [MCP](https://modelcontextprotocol.io/) (Model Context Protocol) Streamable HTTP transport.

**Available actions** (via `@Action` annotation):

| Action | Description |
|--------|-------------|
| `setUrl(String)` | Set the MCP server base URL |
| `prompt(String)` | Send a prompt to the LLM |
| `status()` | Get the LLM service status |
| `listTools()` | List available MCP tools |

## Build

```bash
mvn clean install
```

## Dependencies

- [POJO-actor](https://github.com/scivicslab/POJO-actor)
- [Turing-workflow](https://github.com/scivicslab/Turing-workflow)

## License

[Apache License 2.0](LICENSE)

# Contributing to IDE Agent for Copilot

Thank you for your interest in contributing! This document provides guidelines for contributing to the project.

## Getting Started

1. Fork the repository
2. Clone your fork locally
3. Set up your development environment (see [DEVELOPMENT.md](DEVELOPMENT.md))
4. Create a feature branch from `master`

## Development Setup

- **JDK 21** is required
- **IntelliJ IDEA 2025.1+** (Community or Ultimate)
- Run `./gradlew build` to verify your setup

## Making Changes

1. Create a branch: `git checkout -b feature/your-feature`
2. Make your changes with clear, focused commits
3. Run tests: `./gradlew test`
4. Ensure the plugin builds: `./gradlew :plugin-core:buildPlugin`

## Pull Requests

- Keep PRs focused on a single change
- Include a clear description of what changed and why
- Reference any related issues
- Ensure all tests pass before submitting

## Reporting Issues

- Use GitHub Issues to report bugs or suggest features
- Include steps to reproduce for bug reports
- Include your IDE version and OS

## Code Style

- Follow existing code conventions in the project
- Java/Kotlin files are auto-formatted by IntelliJ defaults
- Add tests for new functionality where practical
- **Do not create large files with comment-based section dividers** — split into multiple files following
  language conventions (one class per file in Java/Kotlin, one module per file in JS, separate files per
  concern in CSS). See [`.github/copilot-instructions.md`](.github/copilot-instructions.md) for details.

## License

By contributing, you agree that your contributions will be licensed under the
[Apache License, Version 2.0](LICENSE).

/** Maps raw MCP tool names to human-friendly chip labels. */

function shortPath(p: string): string {
    if (!p) return '';
    const parts = p.replaceAll('\\', '/').split('/');
    return parts.at(-1) ?? '';
}

function trunc(s: string, max = 24): string {
    return s.length > max ? s.substring(0, max - 1) + '\u2026' : s;
}

function shortClass(fqn: string): string {
    const i = fqn.lastIndexOf('.');
    return i >= 0 ? fqn.substring(i + 1) : fqn;
}

export function toolDisplayName(rawTitle: string, paramsJson?: string): string {
    const name = rawTitle;

    let p: Record<string, any> = {};
    if (paramsJson) {
        try {
            p = JSON.parse(paramsJson);
        } catch { /* ignore */
        }
    }

    const file = shortPath(p.path || p.file || p.scope || '');

    const map: Record<string, () => string> = {
        // OpenCode / Claude built-in agent tools
        'todowrite': () => 'Updating TODO',
        'TodoWrite': () => 'Updating TODO',
        'codesearch': () => p.query ? `Code search: ${trunc(p.query, 20)}` : 'Code search',
        'CodeSearch': () => p.query ? `Code search: ${trunc(p.query, 20)}` : 'Code search',
        'webfetch': () => p.url ? `Fetch: ${trunc(p.url, 24)}` : 'Fetching URL',
        'WebFetch': () => p.url ? `Fetch: ${trunc(p.url, 24)}` : 'Fetching URL',
        'websearch': () => p.query ? `Search: ${trunc(p.query, 20)}` : 'Web search',
        'WebSearch': () => p.query ? `Search: ${trunc(p.query, 20)}` : 'Web search',
        'task': () => p.agent_type || p.agentType ? `Sub-agent: ${p.agent_type || p.agentType}` : 'Sub-agent task',
        'Task': () => p.agent_type || p.agentType ? `Sub-agent: ${p.agent_type || p.agentType}` : 'Sub-agent task',
        'skill': () => p.name ? `Skill: ${p.name}` : 'Using skill',
        'Skill': () => p.name ? `Skill: ${p.name}` : 'Using skill',
        'Read': () => file ? `Reading ${file}` : 'Reading file',
        'Write': () => file ? `Writing ${file}` : 'Writing file',
        'Edit': () => file ? `Editing ${file}` : 'Editing file',
        'Bash': () => p.command ? `Bash: ${trunc(p.command, 24)}` : 'Running bash',
        'Glob': () => p.pattern ? `Glob: ${trunc(p.pattern, 20)}` : 'Finding files',
        'Grep': () => p.pattern ? `Grep: ${trunc(p.pattern, 20)}` : 'Searching files',

        // File operations
        'read_file': () => file ? `Reading ${file}` : 'Reading file',
        'write_file': () => file ? `Editing ${file}` : 'Editing file',
        'create_file': () => file ? `Creating ${file}` : 'Creating file',
        'delete_file': () => file ? `Deleting ${file}` : 'Deleting file',
        'open_in_editor': () => file ? `Opening ${file}` : 'Opening file',
        'show_diff': () => file ? `Diff ${file}` : 'Showing diff',
        'undo': () => file ? `Undo in ${file}` : 'Undoing',

        // Search & navigation
        'search_text': () => p.query ? `Searching \u201c${trunc(p.query, 20)}\u201d` : 'Searching text',
        'search_symbols': () => p.query ? `Finding \u201c${trunc(p.query, 20)}\u201d` : 'Finding symbols',
        'find_references': () => p.symbol ? `Refs: ${p.symbol}` : 'Finding references',
        'go_to_declaration': () => p.symbol ? `Go to ${p.symbol}` : 'Go to declaration',
        'get_file_outline': () => file ? `Outline ${file}` : 'File outline',
        'get_class_outline': () => p.class_name ? `Outline ${shortClass(p.class_name)}` : 'Class outline',
        'get_type_hierarchy': () => p.symbol ? `Hierarchy: ${p.symbol}` : 'Type hierarchy',
        'get_documentation': () => p.symbol ? `Docs: ${trunc(p.symbol, 28)}` : 'Getting docs',
        'list_project_files': () => 'Listing files',
        'list_tests': () => 'Listing tests',

        // Code quality
        'format_code': () => file ? `Formatting ${file}` : 'Formatting code',
        'optimize_imports': () => file ? `Imports ${file}` : 'Optimizing imports',
        'run_inspections': () => file ? `Inspecting ${file}` : 'Running inspections',
        'get_compilation_errors': () => 'Checking compilation',
        'get_problems': () => 'Getting problems',
        'get_highlights': () => 'Getting highlights',
        'apply_quickfix': () => 'Applying quickfix',
        'suppress_inspection': () => 'Suppressing inspection',
        'add_to_dictionary': () => p.word ? `Adding \u201c${p.word}\u201d to dictionary` : 'Adding to dictionary',
        'run_qodana': () => 'Running Qodana',
        'run_sonarqube_analysis': () => 'Running SonarQube',

        // Refactoring
        'refactor': () => {
            if (p.operation === 'rename') return `Renaming ${p.symbol || ''}`;
            return p.operation ? `Refactor: ${p.operation}` : 'Refactoring';
        },

        // Build & run
        'build_project': () => 'Building project',
        'run_command': () => {
            if (p.title) return trunc(p.title, 32);
            return p.command ? `Running ${trunc(p.command, 24)}` : 'Running command';
        },
        'run_tests': () => p.target ? `Testing ${trunc(p.target, 24)}` : 'Running tests',
        'get_test_results': () => 'Test results',
        'get_coverage': () => 'Getting coverage',
        'run_configuration': () => p.name ? `Running \u201c${trunc(p.name, 20)}\u201d` : 'Running config',
        'create_run_configuration': () => p.name ? `Creating config \u201c${trunc(p.name, 16)}\u201d` : 'Creating run config',
        'edit_run_configuration': () => p.name ? `Editing config \u201c${trunc(p.name, 16)}\u201d` : 'Editing run config',
        'list_run_configurations': () => 'Listing run configs',

        // Git
        'git_status': () => 'Git status',
        'git_diff': () => file ? `Git diff ${file}` : 'Git diff',
        'git_commit': () => 'Git commit',
        'git_stage': () => file ? `Staging ${file}` : 'Git stage',
        'git_unstage': () => file ? `Unstaging ${file}` : 'Git unstage',
        'git_log': () => 'Git log',
        'git_blame': () => file ? `Blame ${file}` : 'Git blame',
        'git_show': () => 'Git show',
        'git_branch': () => {
            if (p.action === 'switch') return `Switch to ${p.name}`;
            return p.action === 'create' ? `Create branch ${p.name}` : 'Git branch';
        },
        'git_stash': () => p.action ? `Git stash ${p.action}` : 'Git stash',

        // IDE
        'get_project_info': () => 'Project info',
        'read_ide_log': () => 'Reading IDE log',
        'get_notifications': () => 'Getting notifications',
        'read_run_output': () => 'Reading run output',
        'run_in_terminal': () => 'Running in terminal',
        'read_terminal_output': () => 'Reading terminal',
        'download_sources': () => 'Downloading sources',
        'create_scratch_file': () => p.name ? `Scratch: ${p.name}` : 'Creating scratch',
        'list_scratch_files': () => 'Listing scratches',
        'get_indexing_status': () => 'Indexing status',
        'mark_directory': () => 'Marking directory',
        'get_chat_html': () => 'Getting chat HTML',
        'http_request': () => p.url ? `${p.method || 'GET'} ${trunc(p.url, 28)}` : 'HTTP request',

        // GitHub MCP tools (after prefix stripped to "gh:*")
        'gh:get_file_contents': () => p.path ? `GH: ${shortPath(p.path)}` : 'GH: get file',
        'gh:search_code': () => 'GH: search code',
        'gh:search_repositories': () => 'GH: search repos',
        'gh:search_issues': () => 'GH: search issues',
        'gh:search_pull_requests': () => 'GH: search PRs',
        'gh:search_users': () => 'GH: search users',
        'gh:list_issues': () => 'GH: list issues',
        'gh:list_pull_requests': () => 'GH: list PRs',
        'gh:list_commits': () => 'GH: list commits',
        'gh:list_branches': () => 'GH: list branches',
        'gh:get_commit': () => 'GH: get commit',
        'gh:issue_read': () => p.issue_number ? `GH: issue #${p.issue_number}` : 'GH: read issue',
        'gh:pull_request_read': () => p.pullNumber ? `GH: PR #${p.pullNumber}` : 'GH: read PR',
        'gh:actions_list': () => 'GH: list actions',
        'gh:actions_get': () => 'GH: get action',
        'gh:get_job_logs': () => 'GH: job logs',
    };

    const fn = map[name];
    return fn ? fn() : name;
}

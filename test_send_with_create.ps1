# test_send_with_create.ps1 - 创建对话并发送消息到 SmanClaw Desktop
param(
    [string]$Message = "test"
)

# 先列出项目，获取第一个项目的 ID
try {
    $projectsResponse = Invoke-RestMethod -Uri "http://127.0.0.1:18080/projects" -Method Get -ContentType "application/json"
    Write-Host "Projects: $($To-Json -projectsResponse | ConvertCompress)"
} catch {
    Write-Host "Failed to get projects, trying default approach..."
}

# 使用默认的 project_id 创建对话
$projectId = "7950de30-bd9d-4bd7-ab10-4f73eaff2c27"

# 创建对话
$createConvBody = @{
    project_id = $projectId
    title = "Test Conversation $(Get-Date -Format 'yyyyMMddHHmmss')"
} | ConvertTo-Json -Compress

try {
    $createResponse = Invoke-RestMethod -Uri "http://127.0.0.1:18080/create_conversation" `
        -Method Post `
        -Body ([System.Text.Encoding]::UTF8.GetBytes($createConvBody)) `
        -ContentType "application/json; charset=utf-8"

    Write-Host "Created conversation: $($createResponse | ConvertTo-Json -Compress)"

    if ($createResponse.id) {
        $conversationId = $createResponse.id
    }
} catch {
    Write-Host "Failed to create conversation: $_"
    # 使用默认的对话 ID
    $conversationId = "default"
}

# 发送消息
$body = @{
    content = $Message
    conversation_id = $conversationId
    project_id = $projectId
} | ConvertTo-Json -Compress

try {
    $response = Invoke-RestMethod -Uri "http://127.0.0.1:18080/send" `
        -Method Post `
        -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) `
        -ContentType "application/json; charset=utf-8"

    Write-Host "Send OK: $($response | ConvertTo-Json -Compress)"
} catch {
    Write-Host "Send Failed: $_"
}

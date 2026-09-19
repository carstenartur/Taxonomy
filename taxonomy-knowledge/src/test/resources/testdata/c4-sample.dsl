workspace "Sample C4 Model" {
    model {
        user = person "End User" "A user of the system"
        sys = softwareSystem "Banking System" "Core banking platform" {
            webapp = container "Web Application" "Customer-facing frontend" "React"
            api = container "API Gateway" "REST API layer" "Spring Boot" {
                ctrl = component "Account Controller" "Handles account requests" "Java"
                svc = component "Payment Service" "Processes payments" "Java"
            }
        }
        ext = softwareSystem "External Payment Provider" "Third-party payment gateway"

        user -> webapp "Uses" "HTTPS"
        webapp -> api "Calls" "HTTPS/JSON"
        api -> ext "Sends payments to" "HTTPS"
        ctrl -> svc "Delegates to"
    }
}

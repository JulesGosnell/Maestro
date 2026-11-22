import FlyingFox
import XCTest
import os

@MainActor
struct InputTextRouteHandler : HTTPHandler {
    private let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier!,
        category: String(describing: Self.self)
    )

    func handleRequest(_ request: FlyingFox.HTTPRequest) async throws -> FlyingFox.HTTPResponse {
        guard let requestBody = try? await JSONDecoder().decode(InputTextRequest.self, from: request.bodyData) else {
            return AppError(type: .precondition, message: "incorrect request body provided for input text").httpResponse
        }

        do {
            let start = Date()
            
            // TextInputSequence waits once for keyboard + focus itself; no separate keyboard wait.
            try await TextInputHelper.inputText(requestBody.text)

            let duration = Date().timeIntervalSince(start)
            logger.info("Text input duration took \(duration)")
            return HTTPResponse(statusCode: .ok)
        } catch let error as AppError {
            // Its message names the failure (keyboard detached, no focused field). Wrapping it in
            // localizedDescription would replace that with "The operation couldn't be completed".
            logger.error("\(error.message)")
            return error.httpResponse
        } catch {
            return AppError(message: "Error inputting text: \(error.localizedDescription)").httpResponse
        }
    }
    
}


import Foundation
import FlyingFox

enum AppErrorType: String, Codable {
    case `internal`
    case precondition
    case timeout
}

struct AppError: Error, Codable {
    let type: AppErrorType
    let message: String

    private var statusCode: HTTPStatusCode {
        switch type {
        case .internal: return .internalServerError
        case .precondition: return .badRequest
        case .timeout: return .requestTimeout
        }
    }

    var httpResponse: HTTPResponse {
        let body = try? JSONEncoder().encode(self)
        return HTTPResponse(statusCode: statusCode, body: body ?? Data())
    }

    init(type: AppErrorType = .internal, message: String) {
        self.type = type
        self.message = message
    }

    private enum CodingKeys : String, CodingKey {
        case type = "code"
        case message = "errorMessage"
    }
}

// smithr#1334: without this, an AppError's localizedDescription is Foundation's generic
// "The operation couldn't be completed. (maestro_driver_iosUITests.AppError error 1.)" (1 is the
// code Swift gives every non-enum Error) and the message is lost wherever a handler reports
// error.localizedDescription, as the near-side /waitUntil driverError does.
extension AppError: LocalizedError {
    var errorDescription: String? { message }
}
